/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramFilterEngine;
import com.solegram.messenger.SolegramFilterEngine.Rule;
import com.solegram.messenger.SolegramMessageFilters;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * Editor of one message filter: its kind (words, expression, sender, forwards from a channel), the text with
 * live checking, where it works, a sample text to try it on, a name. Opened from the settings, from the
 * message menu ("Hide similar", filled in from the message) and from a hidden message.
 */
public class SolegramFilterEditActivity extends UniversalFragment {

    private static final int ID_TYPE_WORDS = 1;
    private static final int ID_TYPE_REGEX = 2;
    private static final int ID_TYPE_SENDER = 3;
    private static final int ID_TYPE_FORWARD = 4;
    private static final int ID_WHOLE_WORD = 10;
    private static final int ID_FUZZY = 11;
    private static final int ID_NAMES = 12;
    private static final int ID_PEER = 13;
    private static final int ID_SCOPE_BASE = 20;
    private static final int ID_ENABLED = 30;
    private static final int ID_SAVE = 31;
    private static final int ID_DELETE = 32;
    private static final int MENU_DONE = 1;

    private Rule rule;
    private boolean isNew;
    private String savedState;
    private String sample = "";

    /** Taken from the message the editor was opened for, offered when the kind is switched. */
    private long suggestSender, suggestForward, suggestChat;
    private String suggestSenderName = "", suggestForwardName = "", suggestChatTitle = "";

    private LinearLayout patternView, testView, titleView;
    private EditTextCell patternField, testField, titleField;
    private TextView patternStatus, testStatus;
    private String regexChecked;
    /** null = still checking, "" = fine, else the problem */
    private String regexVerdict = "";
    private int regexCheckId;

    public static SolegramFilterEditActivity create() {
        final SolegramFilterEditActivity a = new SolegramFilterEditActivity();
        a.rule = new Rule();
        a.isNew = true;
        return a;
    }

    public static SolegramFilterEditActivity edit(long id) {
        final Rule r = SolegramMessageFilters.findRule(id);
        if (r == null || r.isPreset()) {
            return create();
        }
        final SolegramFilterEditActivity a = new SolegramFilterEditActivity();
        a.rule = r.copy();
        a.isNew = false;
        return a;
    }

    /** "Hide similar": a new rule filled in from a message - its words, or its forward source or sender when it has no text. */
    public static SolegramFilterEditActivity fromMessage(ChatActivity chat, MessageObject m, MessageObject.GroupedMessages group) {
        final SolegramFilterEditActivity a = create();
        final int account = chat.getCurrentAccount();
        MessageObject textMessage = m;
        if (group != null && TextUtils.isEmpty(m.messageOwner.message)) {
            for (MessageObject g : group.messages) {
                if (!TextUtils.isEmpty(g.messageOwner.message)) {
                    textMessage = g;
                    break;
                }
            }
        }
        final String text = textMessage.messageOwner.message;
        final TLRPC.Chat currentChat = chat.getCurrentChat();
        a.suggestChat = chat.getDialogId();
        a.suggestChatTitle = currentChat != null ? currentChat.title : SolegramMessageFilters.peerName(account, chat.getDialogId());
        if (a.suggestChatTitle == null) {
            a.suggestChatTitle = "";
        }
        // in a group the sender is someone to hide; in a channel the "sender" is the channel itself
        final long sender = m.getFromChatId();
        if (sender != 0 && sender != chat.getDialogId() && currentChat != null && !ChatObject.isChannelAndNotMegaGroup(currentChat)) {
            a.suggestSender = sender;
            a.suggestSenderName = nameOr(SolegramMessageFilters.peerName(account, sender), sender);
        }
        if (m.messageOwner.fwd_from != null && m.messageOwner.fwd_from.from_id != null) {
            a.suggestForward = MessageObject.getPeerId(m.messageOwner.fwd_from.from_id);
            a.suggestForwardName = nameOr(SolegramMessageFilters.peerName(account, a.suggestForward), a.suggestForward);
        }
        if (!TextUtils.isEmpty(text)) {
            a.rule.type = SolegramFilterEngine.TYPE_WORDS;
            a.rule.pattern = SolegramMessageFilters.suggestWords(text);
            a.sample = text.length() > 1000 ? text.substring(0, 1000) : text;
        } else if (a.suggestForward != 0) {
            a.setType(SolegramFilterEngine.TYPE_FORWARD);
        } else if (a.suggestSender != 0) {
            a.setType(SolegramFilterEngine.TYPE_SENDER);
        }
        return a;
    }

    private static String nameOr(String name, long id) {
        return TextUtils.isEmpty(name) ? String.valueOf(id) : name;
    }

    @Override
    public boolean onFragmentCreate() {
        if (rule == null) {
            rule = new Rule();
            isNew = true;
        }
        savedState = state();
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(isNew ? R.string.SG_FilterNew : R.string.SG_FilterEditTitle);
    }

    @Override
    public View createView(Context context) {
        createFields(context);
        super.createView(context);
        listView.setSections();
        actionBar.createMenu().addItem(MENU_DONE, R.drawable.ic_ab_done).setContentDescription(getString(R.string.Save));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == MENU_DONE) {
                    save();
                }
            }
        });
        validatePattern();
        updateTest();
        return fragmentView;
    }

    // region Fields

    private void createFields(Context context) {
        patternField = new EditTextCell(context, "", true, false, SolegramFilterEngine.MAX_PATTERN, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                rule.pattern = newText == null ? "" : newText.toString();
                validatePattern();
                updateTest();
            }
        };
        patternField.setText(rule.pattern);
        patternStatus = statusView(context);
        patternView = column(context, patternField, patternStatus);

        testField = new EditTextCell(context, getString(R.string.SG_FilterTestHint), true, false, 4096, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                sample = newText == null ? "" : newText.toString();
                updateTest();
            }
        };
        testField.setText(sample);
        testStatus = statusView(context);
        testView = column(context, testField, testStatus);

        titleField = new EditTextCell(context, getString(R.string.SG_FilterNameHint), false, false, 64, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                rule.title = newText == null ? "" : newText.toString().trim();
            }
        };
        titleField.setText(rule.title);
        titleView = column(context, titleField, null);
        updatePatternField();
    }

    private TextView statusView(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setPadding(dp(21), 0, dp(21), dp(12));
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout column(Context context, View field, View status) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (status != null) {
            layout.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return layout;
    }

    /** Words may be on several lines; an expression is one line, typed as is (no suggestions or capitals). */
    private void updatePatternField() {
        if (patternField == null) {
            return;
        }
        final boolean regex = rule.type == SolegramFilterEngine.TYPE_REGEX;
        patternField.editText.setHint(getString(regex ? R.string.SG_FilterRegexHint : R.string.SG_FilterWordsHint));
        patternField.editText.setInputType(regex
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (regex) {
            patternField.editText.setTypeface(android.graphics.Typeface.MONOSPACE);
        } else {
            patternField.editText.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    private void setStatus(TextView view, String text, int colorKey) {
        view.setText(text);
        view.setTextColor(Theme.getColor(colorKey, resourceProvider));
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    /** Live check of the words or the expression; an expression is also tried on hard texts in the background. */
    private void validatePattern() {
        if (patternStatus == null) {
            return;
        }
        if (rule.type == SolegramFilterEngine.TYPE_WORDS) {
            final int count = SolegramFilterEngine.splitWords(rule.pattern, rule.fuzzy).length;
            if (count == 0) {
                setStatus(patternStatus, getString(R.string.SG_FilterWordsInfo), Theme.key_windowBackgroundWhiteGrayText4);
            } else {
                setStatus(patternStatus, LocaleController.formatString(R.string.SG_FilterWordsCount, count), Theme.key_windowBackgroundWhiteGrayText4);
            }
            return;
        }
        if (rule.type != SolegramFilterEngine.TYPE_REGEX) {
            return;
        }
        final String pattern = rule.pattern;
        final String problem = SolegramFilterEngine.checkRegex(pattern);
        if (problem != null) {
            regexChecked = pattern;
            regexVerdict = problem;
            setStatus(patternStatus, SolegramMessageFilters.regexProblemText(problem), Theme.key_text_RedRegular);
            return;
        }
        if (pattern.equals(regexChecked) && regexVerdict != null) {
            showRegexVerdict();
            return;
        }
        regexChecked = pattern;
        regexVerdict = null;
        setStatus(patternStatus, getString(R.string.SG_FilterRegexChecking), Theme.key_windowBackgroundWhiteGrayText4);
        final int id = ++regexCheckId;
        final Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
        // its own thread: an expression that never finishes is simply left behind
        final Thread thread = new Thread(() -> {
            final boolean ok = SolegramFilterEngine.stressTest(compiled, 250_000_000L);
            AndroidUtilities.runOnUIThread(() -> onRegexChecked(id, ok));
        }, "SolegramFilterCheck");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        AndroidUtilities.runOnUIThread(() -> onRegexChecked(id, false), 400);
    }

    private void onRegexChecked(int id, boolean ok) {
        if (id != regexCheckId || regexVerdict != null) {
            return;
        }
        regexVerdict = ok ? "" : "slow";
        showRegexVerdict();
    }

    private void showRegexVerdict() {
        if (TextUtils.isEmpty(regexVerdict)) {
            setStatus(patternStatus, getString(R.string.SG_FilterRegexOk), Theme.key_windowBackgroundWhiteGreenText);
        } else {
            setStatus(patternStatus, SolegramMessageFilters.regexProblemText(regexVerdict), Theme.key_text_RedRegular);
        }
    }

    /** Tries the rule's text part on the sample (where it works is not part of the test). */
    private void updateTest() {
        if (testStatus == null) {
            return;
        }
        if (TextUtils.isEmpty(sample)) {
            setStatus(testStatus, getString(R.string.SG_FilterTestInfo), Theme.key_windowBackgroundWhiteGrayText4);
            return;
        }
        final Rule probe = rule.copy();
        probe.enabled = true;
        probe.names = false;
        probe.scope = SolegramFilterEngine.SCOPE_ALL;
        SolegramFilterEngine.compile(probe);
        if (probe.getError() != null) {
            setStatus(testStatus, getString(R.string.SG_FilterTestNoRule), Theme.key_windowBackgroundWhiteGrayText4);
            return;
        }
        final SolegramFilterEngine.Subject subject = new SolegramFilterEngine.Subject();
        subject.reset();
        subject.text = sample;
        subject.where = SolegramFilterEngine.WHERE_GROUP;
        final boolean match = SolegramFilterEngine.firstMatch(Collections.singletonList(probe), subject) != null;
        if (match) {
            setStatus(testStatus, getString(R.string.SG_FilterTestMatch), Theme.key_windowBackgroundWhiteGreenText);
        } else {
            setStatus(testStatus, getString(R.string.SG_FilterTestNoMatch), Theme.key_text_RedRegular);
        }
    }
    // endregion

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final int type = rule.type;
        items.add(UItem.asHeader(getString(R.string.SG_FilterType)));
        items.add(UItem.asRadio(ID_TYPE_WORDS, getString(R.string.SG_FilterTypeWords)).setChecked(type == SolegramFilterEngine.TYPE_WORDS));
        items.add(UItem.asRadio(ID_TYPE_REGEX, getString(R.string.SG_FilterTypeRegex)).setChecked(type == SolegramFilterEngine.TYPE_REGEX));
        items.add(UItem.asRadio(ID_TYPE_SENDER, getString(R.string.SG_FilterTypeSender)).setChecked(type == SolegramFilterEngine.TYPE_SENDER));
        items.add(UItem.asRadio(ID_TYPE_FORWARD, getString(R.string.SG_FilterTypeForward)).setChecked(type == SolegramFilterEngine.TYPE_FORWARD));
        items.add(UItem.asShadow(null));

        if (type == SolegramFilterEngine.TYPE_WORDS || type == SolegramFilterEngine.TYPE_REGEX) {
            final boolean regex = type == SolegramFilterEngine.TYPE_REGEX;
            items.add(UItem.asHeader(getString(regex ? R.string.SG_FilterRegexHeader : R.string.SG_FilterWordsHeader)));
            items.add(UItem.asCustom(patternView));
            if (!regex) {
                items.add(UItem.asCheck(ID_WHOLE_WORD, getString(R.string.SG_FilterWholeWord)).setChecked(rule.wholeWord));
                items.add(UItem.asButtonCheck(ID_FUZZY, getString(R.string.SG_FilterFuzzy), getString(R.string.SG_FilterFuzzyInfo)).setChecked(rule.fuzzy));
            }
            items.add(UItem.asButtonCheck(ID_NAMES, getString(R.string.SG_FilterNames), getString(R.string.SG_FilterNamesInfo)).setChecked(rule.names));
            items.add(UItem.asShadow(getString(regex ? R.string.SG_FilterRegexInfo : R.string.SG_FilterWordsShadow)));
        } else {
            final boolean sender = type == SolegramFilterEngine.TYPE_SENDER;
            items.add(UItem.asHeader(getString(sender ? R.string.SG_FilterSenderHeader : R.string.SG_FilterForwardHeader)));
            final CharSequence value = rule.peerId != 0 ? rule.peerName : getString(R.string.SG_FilterChoose);
            items.add(SolegramFiltersActivity.tile(UItem.asButton(ID_PEER, sender ? R.drawable.msg_user_remove : R.drawable.msg_forward,
                getString(sender ? R.string.SG_FilterSender : R.string.SG_FilterForwardSource), value),
                sender ? new int[]{0xFFFF9500, 0xFFFF9F0A} : new int[]{0xFF5856D6, 0xFF5E5CE6}));
            items.add(UItem.asShadow(getString(sender ? R.string.SG_FilterSenderInfo : R.string.SG_FilterForwardInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.SG_FilterWhere)));
        final int[] scopes = {SolegramFilterEngine.SCOPE_ALL, SolegramFilterEngine.SCOPE_CHANNELS, SolegramFilterEngine.SCOPE_GROUPS,
            SolegramFilterEngine.SCOPE_PRIVATE, SolegramFilterEngine.SCOPE_FEED};
        final int[] scopeNames = {R.string.SG_FilterScopeAll, R.string.SG_FilterScopeChannels, R.string.SG_FilterScopeGroups,
            R.string.SG_FilterScopePrivate, R.string.SG_FilterScopeFeed};
        for (int i = 0; i < scopes.length; i++) {
            items.add(UItem.asRadio(ID_SCOPE_BASE + scopes[i], getString(scopeNames[i])).setChecked(rule.scope == scopes[i]));
        }
        final String chatName = rule.chatId != 0 ? rule.chatTitle : !TextUtils.isEmpty(suggestChatTitle) ? suggestChatTitle : getString(R.string.SG_FilterChoose);
        items.add(UItem.asRadio(ID_SCOPE_BASE + SolegramFilterEngine.SCOPE_CHAT, getString(R.string.SG_FilterScopeChat), chatName)
            .setChecked(rule.scope == SolegramFilterEngine.SCOPE_CHAT));
        items.add(UItem.asShadow(getString(R.string.SG_FilterWhereInfo)));

        if (type == SolegramFilterEngine.TYPE_WORDS || type == SolegramFilterEngine.TYPE_REGEX) {
            items.add(UItem.asHeader(getString(R.string.SG_FilterTest)));
            items.add(UItem.asCustom(testView));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(getString(R.string.SG_FilterName)));
        items.add(UItem.asCustom(titleView));
        items.add(UItem.asShadow(getString(R.string.SG_FilterNameInfo)));

        if (!isNew) {
            items.add(UItem.asCheck(ID_ENABLED, getString(R.string.SG_FilterEnabled)).setChecked(rule.enabled));
            items.add(UItem.asShadow(SolegramFiltersActivity.hitsLine(rule.id)));
        }
        items.add(UItem.asButton(ID_SAVE, R.drawable.msg_check_s, getString(R.string.SG_FilterSave)).accent());
        if (!isNew) {
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, getString(R.string.SG_FilterDelete)).red());
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;
        if (id >= ID_TYPE_WORDS && id <= ID_TYPE_FORWARD) {
            setType(id - ID_TYPE_WORDS);
            updatePatternField();
            validatePattern();
            updateTest();
            listView.adapter.update(true);
            if ((rule.type == SolegramFilterEngine.TYPE_SENDER || rule.type == SolegramFilterEngine.TYPE_FORWARD) && rule.peerId == 0) {
                pickPeer();
            }
            return;
        }
        if (id >= ID_SCOPE_BASE && id <= ID_SCOPE_BASE + SolegramFilterEngine.SCOPE_CHAT) {
            final int scope = id - ID_SCOPE_BASE;
            if (scope == SolegramFilterEngine.SCOPE_CHAT) {
                if (rule.chatId == 0 && suggestChat != 0) {
                    rule.chatId = suggestChat;
                    rule.chatTitle = suggestChatTitle;
                } else if (rule.chatId == 0 || rule.scope == SolegramFilterEngine.SCOPE_CHAT) {
                    // no chat yet, or tapped again: pick one
                    pickChat();
                    return;
                }
            }
            rule.scope = scope;
            listView.adapter.update(true);
            return;
        }
        switch (id) {
            case ID_WHOLE_WORD:
                rule.wholeWord = !rule.wholeWord;
                setChecked(view, rule.wholeWord);
                updateTest();
                return;
            case ID_FUZZY:
                rule.fuzzy = !rule.fuzzy;
                setChecked(view, rule.fuzzy);
                updateTest();
                return;
            case ID_NAMES:
                rule.names = !rule.names;
                setChecked(view, rule.names);
                return;
            case ID_ENABLED:
                rule.enabled = !rule.enabled;
                setChecked(view, rule.enabled);
                return;
            case ID_PEER:
                pickPeer();
                return;
            case ID_SAVE:
                save();
                return;
            case ID_DELETE:
                confirmDelete();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void setType(int type) {
        rule.type = type;
        // the sender or forward source of the message the editor came from
        if (type == SolegramFilterEngine.TYPE_SENDER && (rule.peerId == 0 || rule.peerId == suggestForward) && suggestSender != 0) {
            rule.peerId = suggestSender;
            rule.peerName = suggestSenderName;
        } else if (type == SolegramFilterEngine.TYPE_FORWARD && (rule.peerId == 0 || rule.peerId == suggestSender) && suggestForward != 0) {
            rule.peerId = suggestForward;
            rule.peerName = suggestForwardName;
        }
        if (type == SolegramFilterEngine.TYPE_SENDER && isNew && rule.scope == SolegramFilterEngine.SCOPE_ALL) {
            // hiding someone is meant for groups: in a private chat it would hide the whole conversation
            rule.scope = SolegramFilterEngine.SCOPE_GROUPS;
        }
    }

    private static void setChecked(View view, boolean checked) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(checked);
        } else if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(checked);
        }
    }

    // region Pickers

    private void pickPeer() {
        final boolean sender = rule.type == SolegramFilterEngine.TYPE_SENDER;
        openPicker(sender ? DialogsActivity.DIALOGS_TYPE_DEFAULT : DialogsActivity.DIALOGS_TYPE_CHANNELS_ONLY, dialogId -> {
            rule.peerId = dialogId;
            rule.peerName = nameOr(SolegramMessageFilters.peerName(currentAccount, dialogId), dialogId);
            listView.adapter.update(true);
        });
    }

    private void pickChat() {
        openPicker(DialogsActivity.DIALOGS_TYPE_DEFAULT, dialogId -> {
            rule.chatId = dialogId;
            rule.chatTitle = nameOr(SolegramMessageFilters.peerName(currentAccount, dialogId), dialogId);
            rule.scope = SolegramFilterEngine.SCOPE_CHAT;
            listView.adapter.update(true);
        });
    }

    private void openPicker(int dialogsType, Utilities.Callback<Long> onPicked) {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false);
        args.putInt("dialogsType", dialogsType);
        final DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((picker, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                onPicked.run(dids.get(0).dialogId);
            }
            picker.finishFragment();
            return true;
        });
        presentFragment(fragment);
    }
    // endregion

    // region Saving

    /** What the user can change, to know whether leaving loses anything. */
    private String state() {
        return rule.type + "|" + rule.pattern + "|" + rule.title + "|" + rule.wholeWord + rule.fuzzy + rule.names + rule.enabled
            + "|" + rule.scope + "|" + rule.chatId + "|" + rule.peerId;
    }

    private boolean hasChanges() {
        return savedState != null && !savedState.equals(state());
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasChanges() && getParentActivity() != null) {
            if (invoked) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                builder.setTitle(getString(R.string.UnsavedChanges));
                builder.setMessage(getString(R.string.SG_FilterUnsaved));
                builder.setPositiveButton(getString(R.string.Save), (d, w) -> save());
                builder.setNegativeButton(getString(R.string.PassportDiscard), (d, w) -> {
                    savedState = state();
                    finishFragment();
                });
                showDialog(builder.create());
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean canBeginSlide() {
        return !hasChanges() && super.canBeginSlide();
    }

    private void save() {
        final String problem = problem();
        if (problem != null) {
            BulletinFactory.of(this).createErrorBulletin(problem).show();
            try {
                AndroidUtilities.shakeViewSpring(patternView, 4);
            } catch (Throwable ignore) {
            }
            return;
        }
        if (rule.scope == SolegramFilterEngine.SCOPE_CHAT && rule.chatId == 0) {
            rule.scope = SolegramFilterEngine.SCOPE_ALL;
        }
        rule.title = rule.title == null ? "" : rule.title.trim();
        rule.pattern = rule.type == SolegramFilterEngine.TYPE_REGEX ? rule.pattern.trim() : rule.pattern;
        SolegramMessageFilters.saveRule(rule);
        savedState = state();
        finishFragment();
    }

    /** Why the rule cannot be saved yet, or null. */
    private String problem() {
        switch (rule.type) {
            case SolegramFilterEngine.TYPE_WORDS:
                return SolegramFilterEngine.splitWords(rule.pattern, rule.fuzzy).length == 0 ? getString(R.string.SG_FilterPatternEmpty) : null;
            case SolegramFilterEngine.TYPE_REGEX: {
                final String pattern = rule.pattern.trim();
                final String check = SolegramFilterEngine.checkRegex(pattern);
                if (check != null) {
                    return SolegramMessageFilters.regexProblemText(check);
                }
                if (!rule.pattern.equals(regexChecked) || regexVerdict == null) {
                    validatePattern();
                    return getString(R.string.SG_FilterRegexWait);
                }
                return TextUtils.isEmpty(regexVerdict) ? null : SolegramMessageFilters.regexProblemText(regexVerdict);
            }
            default:
                return rule.peerId == 0 ? getString(R.string.SG_FilterChooseFirst) : null;
        }
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_FilterDelete))
            .setMessage(getString(R.string.SG_FilterDeleteConfirm))
            .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                SolegramMessageFilters.deleteRule(rule.id);
                savedState = state();
                finishFragment();
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
    // endregion
}
