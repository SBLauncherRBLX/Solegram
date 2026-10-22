/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramReminders;
import com.solegram.messenger.SolegramSummarizer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * "Remind me" for messages: the reminder text (composed by AI from the messages, editable), when, how often.
 * The text is composed while the sheet is open (Gemini Nano works only in the foreground); the reminder can be
 * created at any moment, and an answer that comes later still replaces the text the user did not touch.
 */
public class SolegramReminderSheet extends BottomSheet {

    private static final int[] ORANGE = {0xFFFF9500, 0xFFFF9F0A};

    private static final int WHEN_1H = 0;
    private static final int WHEN_3H = 1;
    private static final int WHEN_EVENING = 2;
    private static final int WHEN_MORNING = 3;
    private static final int WHEN_WEEKEND = 4;
    private static final int WHEN_CUSTOM = 5;

    private final BaseFragment fragment;
    private final SolegramReminders.Source source;
    private final String[] fallback;

    private final EditTextBoldCursor titleField;
    private final EditTextBoldCursor bodyField;
    private final LinearLayout fields;
    private final Shimmer shimmer;
    private final ImageView statusIcon;
    private final TextView statusText;
    private final TextView regenerate;
    private final TextView summary;
    private final ButtonWithCounterView createButton;

    private final ArrayList<Choice> whenChoices = new ArrayList<>();
    private final Choice[] repeatChoices = new Choice[3];
    private Choice chosenWhen;
    private int repeat = SolegramReminders.REPEAT_NONE;
    private long customTime;

    private int requestToken;
    private boolean composing;
    private boolean edited;
    private boolean settingText;
    private int textSource;
    private String lastAiTitle;
    /** The reminder made while the AI was still composing: its text follows the answer. */
    private int createdId;

    public static void show(ChatActivity chat, List<MessageObject> messages) {
        if (chat == null || chat.getParentActivity() == null) {
            return;
        }
        final SolegramReminders.Source source = SolegramReminders.collect(chat, messages);
        if (source == null) {
            BulletinFactory.of(chat).createErrorBulletin(getString(R.string.SG_ReminderNoMessages)).show();
            return;
        }
        // shown on its own (not as the fragment's dialog): the AI consent question may open above it
        new SolegramReminderSheet(chat, source).show();
    }

    private SolegramReminderSheet(BaseFragment fragment, SolegramReminders.Source source) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.source = source;
        this.fallback = SolegramReminders.fallbackText(source);
        final Context context = fragment.getParentActivity();
        fixNavigationBar();
        setApplyBottomPadding(false);

        final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        final int grayColor = getThemedColor(Theme.key_dialogTextGray3);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // the button stays on screen, also with the keyboard open
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.62f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(20), 0, dp(4));
        scroll.addView(content);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // header: bell tile, title, where from
        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(PluginSecuritySheet.tileIcon(context, R.drawable.msg_notifications, ORANGE, 42), LayoutHelper.createLinear(42, 42));
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        final TextView heading = text(context, getString(R.string.SG_ReminderSheetTitle), 19, textColor);
        heading.setTypeface(AndroidUtilities.bold());
        titles.addView(heading);
        String from = TextUtils.isEmpty(source.chatName) ? "" : LocaleController.formatString(R.string.SG_ReminderFromChat, source.chatName);
        if (source.lines.size() > 1) {
            final String count = LocaleController.formatString(R.string.SG_ReminderMessagesCount, source.lines.size());
            from = from.isEmpty() ? count : from + " · " + count;
        }
        final TextView sub = text(context, from, 14, grayColor);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        head.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        content.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

        // the text card: title and details, a shimmer while the AI composes, who made it and "make again"
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(16), ColorUtils.setAlphaComponent(textColor, 0x0F)));
        card.setPadding(dp(14), dp(10), dp(14), dp(4));

        final FrameLayout textFrame = new FrameLayout(context);
        fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        titleField = field(context, 17, textColor, accent, getString(R.string.SG_ReminderTitleHint), 100, 3);
        titleField.setTypeface(AndroidUtilities.bold());
        titleField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        fields.addView(titleField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bodyField = field(context, 15, ColorUtils.setAlphaComponent(textColor, 0xD0), accent, getString(R.string.SG_ReminderBodyHint), 400, 5);
        fields.addView(bodyField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        textFrame.addView(fields, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        shimmer = new Shimmer(context, textColor);
        shimmer.setOnClickListener(v -> stopWaiting(true)); // typing one's own text need not wait for the AI
        textFrame.addView(shimmer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        card.addView(textFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout status = new LinearLayout(context);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusIcon = new ImageView(context);
        statusIcon.setImageResource(R.drawable.sg_ai_summary);
        statusIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(grayColor, android.graphics.PorterDuff.Mode.SRC_IN));
        statusIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        status.addView(statusIcon, LayoutHelper.createLinear(16, 16, 0, 0, 6, 0));
        statusText = text(context, "", 13, grayColor);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        status.addView(statusText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        regenerate = text(context, getString(R.string.SG_ReminderRegenerate), 13, accent);
        regenerate.setTypeface(AndroidUtilities.bold());
        regenerate.setGravity(Gravity.CENTER_VERTICAL);
        final android.graphics.drawable.Drawable retry = context.getResources().getDrawable(R.drawable.msg_retry).mutate();
        retry.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN));
        retry.setBounds(0, 0, dp(16), dp(16));
        regenerate.setCompoundDrawables(retry, null, null, null);
        regenerate.setCompoundDrawablePadding(dp(4));
        regenerate.setPadding(dp(8), dp(8), dp(4), dp(8));
        regenerate.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8));
        regenerate.setOnClickListener(v -> compose(true));
        status.addView(regenerate, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, -4, 0));
        card.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 4, 0, 0));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // when: the usual choices in a grid, any date and time below
        content.addView(sectionHeader(context, getString(R.string.SG_ReminderWhen)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 8));
        final long now = System.currentTimeMillis();
        addWhen(context, WHEN_1H, R.string.SG_ReminderIn1h, SolegramReminders.roundToMinute(now + SolegramReminders.HOUR));
        addWhen(context, WHEN_3H, R.string.SG_ReminderIn3h, SolegramReminders.roundToMinute(now + 3 * SolegramReminders.HOUR));
        final long evening = atMinutes(0, SolegramConfig.reminderEvening);
        if (evening > now + 10 * SolegramReminders.MINUTE) {
            addWhen(context, WHEN_EVENING, R.string.SG_ReminderEvening, evening);
        }
        addWhen(context, WHEN_MORNING, R.string.SG_ReminderMorning, atMinutes(1, SolegramConfig.reminderMorning));
        addWhen(context, WHEN_WEEKEND, R.string.SG_ReminderWeekend, weekend(now));
        final LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < whenChoices.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, i == 0 ? 0 : 8, 0, 0));
            }
            row.addView(whenChoices.get(i), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, i % 2 == 0 ? 0 : 8, 0, 0, 0));
        }
        if (whenChoices.size() % 2 == 1) {
            row.addView(new View(context), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 8, 0, 0, 0));
        }
        final Choice custom = new Choice(context, WHEN_CUSTOM, getString(R.string.SG_ReminderPick), null);
        custom.setOnClickListener(v -> pickCustom());
        whenChoices.add(custom);
        grid.addView(custom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));
        content.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // how often
        content.addView(sectionHeader(context, getString(R.string.SG_ReminderRepeat)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 8));
        final LinearLayout repeatRow = new LinearLayout(context);
        repeatRow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] repeatNames = {R.string.SG_ReminderRepeatNone, R.string.SG_ReminderRepeatDaily, R.string.SG_ReminderRepeatWeekly};
        for (int i = 0; i < 3; i++) {
            final int value = i;
            final Choice c = new Choice(context, i, getString(repeatNames[i]), null);
            c.setOnClickListener(v -> setRepeat(value, true));
            repeatChoices[i] = c;
            repeatRow.addView(c, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, i == 0 ? 0 : 8, 0, 0, 0));
        }
        content.addView(repeatRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 16, 0, 16, 0));

        summary = text(context, "", 14, grayColor);
        summary.setGravity(Gravity.CENTER);
        root.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
        createButton = new ButtonWithCounterView(context, resourcesProvider);
        createButton.setText(getString(R.string.SG_ReminderCreate), false);
        createButton.setOnClickListener(v -> createReminder());
        root.addView(createButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 16));

        setCustomView(root);
        solegramReadingGlass(); // text to read and edit: the denser glass of Solegram's other sheets

        // tonight when it is still ahead, otherwise tomorrow morning
        Choice first = null;
        for (Choice c : whenChoices) {
            if (c.kind == WHEN_EVENING || first == null && c.kind == WHEN_MORNING) {
                first = c;
            }
        }
        choose(first, false);
        setRepeat(SolegramReminders.REPEAT_NONE, false);
        start();
    }

    // region Text

    private void start() {
        if (!SolegramConfig.reminderAi || !source.aiAllowed) {
            applyText(fallback[0], fallback[1], 0);
            setStatus(getString(!SolegramConfig.reminderAi ? R.string.SG_ReminderTextFromMessages
                : source.secret ? R.string.SG_ReminderNoAiSecret : R.string.SG_ReminderNoAiProtected), false);
            regenerate.setVisibility(View.GONE);
            shimmer.setVisibility(View.GONE);
            return;
        }
        compose(false);
    }

    private void compose(boolean asked) {
        final int token = ++requestToken;
        composing = true;
        setLoading(true);
        setStatus(getString(R.string.SG_ReminderComposing), true);
        regenerate.setEnabled(false);
        regenerate.animate().alpha(0.4f).setDuration(150).start();
        SolegramReminders.compose(source, asked ? lastAiTitle : null, asked, (title, body, code) -> {
            if (token != requestToken) {
                return; // the user stopped waiting or asked again
            }
            composing = false;
            if (createdId != 0) {
                if (title != null && !edited) {
                    SolegramReminders.updateText(createdId, title, body, code);
                }
                return;
            }
            if (title != null) {
                lastAiTitle = title;
                if (asked || !edited) {
                    applyText(title, body, code);
                }
                setStatus(LocaleController.formatString(R.string.SG_ReminderAiBy, sourceName(code)), true);
            } else {
                // the text stays: the one from the messages (it waited under the shimmer), or the earlier answer
                setStatus(getString(failureText(code)), false);
            }
            regenerate.setText(getString(code == SolegramSummarizer.COMPOSE_NEEDS_CONSENT ? R.string.SG_ReminderAiCompose : R.string.SG_ReminderRegenerate));
            regenerate.setEnabled(true);
            regenerate.animate().alpha(1f).setDuration(150).start();
            setLoading(false);
        });
    }

    /** The shimmer was tapped: the text from the messages, to edit right away. */
    private void stopWaiting(boolean focus) {
        if (!composing) {
            return;
        }
        requestToken++;
        composing = false;
        applyText(fallback[0], fallback[1], 0);
        setStatus(getString(R.string.SG_ReminderTextFromMessages), false);
        regenerate.setEnabled(true);
        regenerate.animate().alpha(1f).setDuration(150).start();
        setLoading(false);
        if (focus) {
            titleField.requestFocus();
            titleField.setSelection(titleField.length());
            AndroidUtilities.showKeyboard(titleField);
        }
    }

    private static String sourceName(int code) {
        switch (code) {
            case SolegramSummarizer.SOURCE_NANO:
                return getString(R.string.SG_AiSourceNano);
            case SolegramSummarizer.SOURCE_OWN:
                return getString(R.string.SG_AiSourceOwn);
            default:
                return getString(R.string.SG_AiSourceFree);
        }
    }

    private static int failureText(int code) {
        switch (code) {
            case SolegramSummarizer.COMPOSE_NEEDS_CONSENT:
                return R.string.SG_ReminderAiNeedsConsent;
            case SolegramSummarizer.COMPOSE_NO_SERVICE:
                return R.string.SG_ReminderAiNoService;
            case SolegramSummarizer.COMPOSE_NANO_MISSING:
                return R.string.SG_ReminderAiNoNano;
            default:
                return R.string.SG_ReminderAiFailed;
        }
    }

    private void applyText(String title, String body, int source) {
        settingText = true;
        titleField.setText(title);
        bodyField.setText(body);
        settingText = false;
        edited = false;
        textSource = source;
    }

    private void setStatus(CharSequence text, boolean ai) {
        statusText.setText(text);
        statusIcon.setImageResource(ai ? R.drawable.sg_ai_summary : R.drawable.msg_message);
    }

    private void setLoading(boolean loading) {
        if (loading) {
            shimmer.setVisibility(View.VISIBLE);
            shimmer.animate().cancel();
            shimmer.setAlpha(1f);
            fields.animate().cancel();
            fields.setAlpha(0f);
            // the text from the messages waits under the shimmer: it keeps the size, and "Create" uses it meanwhile
            if (titleField.length() == 0) {
                applyText(fallback[0], fallback[1], 0);
            }
        } else {
            fields.animate().alpha(1f).setDuration(220).start();
            shimmer.animate().alpha(0f).setDuration(220).withEndAction(() -> shimmer.setVisibility(View.GONE)).start();
        }
    }
    // endregion

    // region When

    private void addWhen(Context context, int kind, int label, long time) {
        final String sub = kind == WHEN_WEEKEND && SolegramReminders.daysFromToday(time) > 0
            ? LocaleController.getInstance().getFormatterWeek().format(time) + ", " + SolegramReminders.time(time)
            : SolegramReminders.formatShort(time, kind == WHEN_MORNING);
        final Choice c = new Choice(context, kind, getString(label), sub);
        c.time = time;
        c.setOnClickListener(v -> choose(c, true));
        whenChoices.add(c);
    }

    /** Today ({@code days} = 0) or a later day at the given minutes after midnight. */
    private static long atMinutes(int days, int minutes) {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The coming Saturday, an hour later than the morning time (a weekend morning). */
    private static long weekend(long now) {
        final int minutes = Math.min(SolegramConfig.reminderMorning + 60, 23 * 60 + 59);
        final int days = (Calendar.SATURDAY - Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 7) % 7;
        long t = atMinutes(days, minutes);
        if (t <= now) {
            t = atMinutes(days + 7, minutes);
        }
        return t;
    }

    private void choose(Choice c, boolean animated) {
        if (c == null) {
            return;
        }
        chosenWhen = c;
        for (Choice other : whenChoices) {
            other.setChosen(other == c, animated);
        }
        updateSummary();
    }

    private void setRepeat(int value, boolean animated) {
        repeat = value;
        for (int i = 0; i < repeatChoices.length; i++) {
            repeatChoices[i].setChosen(i == value, animated);
        }
        updateSummary();
    }

    private void pickCustom() {
        final long current = customTime != 0 ? customTime : chosenWhen != null ? chosenWhen.time : System.currentTimeMillis() + SolegramReminders.HOUR;
        AlertsCreator.createDatePickerDialog(getContext(), getString(R.string.SG_ReminderPickTitle), getString(R.string.SG_ReminderPickButton),
            current / 1000, (notify, date, repeatPeriod) -> {
                customTime = SolegramReminders.roundToMinute(date * 1000L);
                final Choice custom = whenChoices.get(whenChoices.size() - 1);
                custom.time = customTime;
                custom.setSubtitle(SolegramReminders.formatAt(customTime));
                choose(custom, true);
            });
    }

    /** The chosen time; "in an hour" counts from now, however long the sheet was open. */
    private long dueTime() {
        if (chosenWhen == null) {
            return 0;
        }
        final long now = System.currentTimeMillis();
        if (chosenWhen.kind == WHEN_1H) {
            return SolegramReminders.roundToMinute(now + SolegramReminders.HOUR);
        }
        if (chosenWhen.kind == WHEN_3H) {
            return SolegramReminders.roundToMinute(now + 3 * SolegramReminders.HOUR);
        }
        return chosenWhen.time;
    }

    private void updateSummary() {
        final long due = dueTime();
        if (due == 0) {
            summary.setText("");
            createButton.setEnabled(false);
            return;
        }
        createButton.setEnabled(true);
        final int format = repeat == SolegramReminders.REPEAT_DAILY ? R.string.SG_ReminderSummaryDaily
            : repeat == SolegramReminders.REPEAT_WEEKLY ? R.string.SG_ReminderSummaryWeekly : R.string.SG_ReminderSummary;
        summary.setText(LocaleController.formatString(format, SolegramReminders.formatAt(due)));
    }
    // endregion

    // region Create

    private void createReminder() {
        long due = dueTime();
        if (due == 0) {
            return;
        }
        due = Math.max(due, SolegramReminders.roundToMinute(System.currentTimeMillis()) + SolegramReminders.MINUTE);
        String title = titleField.getText().toString().trim();
        String body = bodyField.getText().toString().trim();
        if (title.isEmpty()) {
            title = body.isEmpty() ? fallback[0] : SolegramReminders.clip(body, SolegramReminders.TITLE_MAX);
            if (body.equals(title)) {
                body = "";
            }
        }
        final SolegramReminders.Reminder r = SolegramReminders.create(source.account, source.dialogId, source.topicId, source.messageIds,
            source.chatName, title, body, edited ? 0 : textSource, due, repeat);
        if (composing && !edited) {
            createdId = r.id; // the answer still coming fills it in
        } else {
            requestToken++;
        }
        AndroidUtilities.hideKeyboard(titleField);
        dismiss();
        showCreated(fragment, r);
    }

    /** "Reminder for tomorrow at 9:00", undo, and the way to all reminders (or to turned-off notifications). */
    private static void showCreated(BaseFragment fragment, SolegramReminders.Reminder r) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final boolean notificationsOn = notificationsAllowed(context);
        if (!notificationsOn && Build.VERSION.SDK_INT >= 33 && !PermissionRequest.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            // Telegram's own question; refused (now or for good), the bulletin says notifications are off
            createdBulletin(fragment, r, false);
            PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> {
                if (!granted) {
                    createdBulletin(fragment, r, true);
                }
            });
        } else {
            createdBulletin(fragment, r, !notificationsOn);
        }

        if (notificationsOn && !SolegramReminders.canScheduleExact() && !SolegramConfig.reminderExactAsked) {
            // once: why it may come a few minutes late, and where to allow the exact time
            SolegramConfig.reminderExactAsked = true;
            SolegramConfig.prefs().edit().putBoolean("reminderExactAsked", true).apply(); // no reload: nothing on screen depends on it
            final AlertDialog dialog = new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getString(R.string.SG_ReminderExactTitle))
                .setMessage(getString(R.string.SG_ReminderExactInfo))
                .setPositiveButton(getString(R.string.SG_ReminderExactAllow), (d, w) -> openExactSettings(context))
                .setNegativeButton(getString(R.string.SG_ReminderExactLater), null)
                .create();
            fragment.showDialog(dialog);
        }
    }

    private static void createdBulletin(BaseFragment fragment, SolegramReminders.Reminder r, boolean notificationsOff) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final CharSequence subtitle = notificationsOff
            ? AndroidUtilities.replaceArrows(AndroidUtilities.replaceSingleTag(getString(R.string.SG_ReminderNotificationsOff), () -> openNotificationSettings(context)), true)
            : AndroidUtilities.replaceArrows(AndroidUtilities.replaceSingleTag(getString(R.string.SG_ReminderAllLink), () -> fragment.presentFragment(new SolegramRemindersActivity())), true);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_unmute,
            LocaleController.formatString(R.string.SG_ReminderSetFor, SolegramReminders.formatAt(r.due)), subtitle,
            getString(R.string.SG_ReminderUndo), () -> SolegramReminders.delete(r.id)).show();
    }

    /** Notifications of the app and of the reminders channel are on. */
    static boolean notificationsAllowed(Context context) {
        final NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (!nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            final android.app.NotificationManager system = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            final android.app.NotificationChannel channel = system != null ? system.getNotificationChannel("solegram_reminders") : null;
            return channel == null || channel.getImportance() != android.app.NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static void openNotificationSettings(Context context) {
        try {
            final Intent intent;
            if (Build.VERSION.SDK_INT >= 26 && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                intent = SolegramReminders.channelSettingsIntent(context); // only the reminders are off
            } else if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else {
                intent = SolegramReminders.channelSettingsIntent(context);
            }
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    static void openExactSettings(Context context) {
        final Intent intent = SolegramReminders.exactAlarmSettingsIntent();
        if (intent == null) {
            return;
        }
        try {
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void dismiss() {
        if (createdId == 0) {
            requestToken++; // closed without creating: a late answer has nowhere to go
        }
        super.dismiss();
    }
    // endregion

    // region Views

    private EditTextBoldCursor field(Context context, float size, int color, int accent, String hint, int maxLength, int maxLines) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setBackground(null);
        field.setPadding(0, dp(4), 0, dp(4));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        field.setTextColor(color);
        field.setHintText(hint);
        field.setHintColor(getThemedColor(Theme.key_dialogTextHint));
        field.setCursorColor(accent);
        field.setCursorSize(dp(size + 3));
        field.setCursorWidth(1.5f);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMaxLines(maxLines);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!settingText) {
                    edited = true;
                }
            }
        });
        return field;
    }

    private TextView text(Context context, CharSequence value, float size, int color) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        return view;
    }

    private TextView sectionHeader(Context context, String value) {
        final TextView view = text(context, value, 15, getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    /** A rounded choice: a soft fill, the accent fill and outline when chosen. */
    private class Choice extends LinearLayout {

        final int kind;
        long time;
        private final TextView title;
        private final TextView subtitle;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        private final int grayColor = getThemedColor(Theme.key_dialogTextGray3);
        private final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        private float progress;
        private boolean chosen;
        private ValueAnimator animator;

        Choice(Context context, int kind, CharSequence label, CharSequence sub) {
            super(context);
            this.kind = kind;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setWillNotDraw(false);
            setPadding(dp(10), 0, dp(10), 0);
            title = text(context, label, 14, textColor);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            subtitle = text(context, sub, 12.5f, grayColor);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setVisibility(TextUtils.isEmpty(sub) ? GONE : VISIBLE);
            addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 1, 0, 0));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            setForeground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 14, 14));
            ScaleStateListAnimator.apply(this, .04f, 1.2f);
            updateColors();
        }

        void setSubtitle(CharSequence sub) {
            subtitle.setText(sub);
            subtitle.setVisibility(TextUtils.isEmpty(sub) ? GONE : VISIBLE);
        }

        void setChosen(boolean value, boolean animated) {
            if (chosen == value && (animator != null || progress == (value ? 1f : 0f))) {
                return;
            }
            chosen = value;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated) {
                progress = value ? 1f : 0f;
                updateColors();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                updateColors();
            });
            animator.setDuration(240);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        private void updateColors() {
            title.setTextColor(ColorUtils.blendARGB(textColor, accent, progress));
            subtitle.setTextColor(ColorUtils.blendARGB(grayColor, accent, progress * 0.8f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float inset = dp(0.75f);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            final float r = dp(14);
            fill.setColor(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(textColor, 0x0D), ColorUtils.setAlphaComponent(accent, 0x24), progress));
            canvas.drawRoundRect(rect, r, r, fill);
            if (progress > 0) {
                stroke.setColor(ColorUtils.setAlphaComponent(accent, (int) (0xFF * progress)));
                canvas.drawRoundRect(rect, r, r, stroke);
            }
            super.onDraw(canvas);
        }
    }

    /** Two lines of text that are not there yet: soft bars with a light passing over them. */
    private static class Shimmer extends View {

        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private LinearGradient gradient;
        private final int color;

        Shimmer(Context context, int color) {
            super(context);
            this.color = color;
            bar.setColor(ColorUtils.setAlphaComponent(color, 0x16));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            gradient = new LinearGradient(0, 0, dp(160), 0, new int[]{0, ColorUtils.setAlphaComponent(color, 0x1C), 0},
                null, Shader.TileMode.CLAMP);
            light.setShader(gradient);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (gradient == null) {
                return;
            }
            final float w = getWidth();
            final float x = ((SystemClock.uptimeMillis() % 1500) / 1500f) * (w + dp(320)) - dp(160);
            matrix.setTranslate(x, 0);
            gradient.setLocalMatrix(matrix);
            final float r = dp(6);
            final float[][] bars = {{dp(7), dp(19), 0.72f}, {dp(31), dp(41), 0.94f}, {dp(48), dp(58), 0.58f}};
            for (float[] b : bars) {
                if (b[1] > getHeight()) {
                    break;
                }
                rect.set(0, b[0], w * b[2], b[1]);
                canvas.drawRoundRect(rect, r, r, bar);
                canvas.drawRoundRect(rect, r, r, light);
            }
            if (getVisibility() == VISIBLE && getAlpha() > 0) {
                invalidate();
            }
        }
    }
    // endregion
}
