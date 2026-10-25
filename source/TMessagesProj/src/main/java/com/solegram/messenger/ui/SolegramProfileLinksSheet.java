/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramNowPlayingBio;
import com.solegram.messenger.SolegramProfileLinks;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * The editor of the profile links: the bio's own words, the links (picked from a grid of platforms, a username
 * typed or a link pasted; dragged into order, removed), one per line or in a row, and a preview of the profile
 * in Solegram and in Telegram with what is left of the bio's 70 (140) characters. Saves the bio the way Telegram
 * does, or hands it to the profile editor that opened it.
 */
public class SolegramProfileLinksSheet extends BottomSheet {

    private static final int[] BLUE = {0xFF2F8CFF, 0xFF4FA3FF};
    private static final int ROW_HEIGHT = 58;

    private final BaseFragment fragment;
    private final int account;
    private final int limit;
    @Nullable
    private final Utilities.Callback<String> onApply;
    private final String initialAbout;

    private final ArrayList<SolegramProfileLinks.Link> links = new ArrayList<>();
    private boolean row;

    private final LinearLayout content;
    private final EditTextBoldCursor textField;
    private final LinksList list;
    private final TextView emptyText;
    private final SolegramProfileLinksView chipsPreview;
    private final TextView telegramPreview;
    private final TextView counter;
    private final TextView warning;
    private final Choice columnChoice, rowChoice;
    private final ButtonWithCounterView saveButton;

    // adding or changing a link
    private final TextView addButton;
    private final LinearLayout gridPanel;
    private final LinearLayout inputPanel;
    private final ImageView inputIcon;
    private final TextView inputTitle;
    private final TextView inputPrefix;
    private final EditTextBoldCursor inputField;
    private final TextView inputError;
    private final ButtonWithCounterView inputButton;
    private SolegramProfileLinks.Platform inputPlatform;
    /** The link being changed, -1 for a new one. */
    private int editingIndex = -1;
    private boolean saving;

    /** From the profile editor: the bio being edited comes back changed (saved with the editor's Done). */
    public static void show(BaseFragment fragment, String about, Utilities.Callback<String> onApply) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new SolegramProfileLinksSheet(fragment, about, onApply).show();
    }

    /** On its own (the profile, Solegram's settings): reads the bio from Telegram and saves it there. */
    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final TLRPC.UserFull cached = MessagesController.getInstance(account).getUserFull(UserConfig.getInstance(account).getClientUserId());
        if (cached != null) {
            new SolegramProfileLinksSheet(fragment, cached.about, null).show();
            return;
        }
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        final TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        req.id = MessagesController.getInstance(account).getInputUser(self);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            progress.dismiss();
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (!(response instanceof TLRPC.TL_users_userFull)) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.UnknownError)).show();
                return;
            }
            final TLRPC.TL_users_userFull res = (TLRPC.TL_users_userFull) response;
            MessagesController.getInstance(account).putUsers(res.users, false);
            new SolegramProfileLinksSheet(fragment, res.full_user.about, null).show();
        }));
    }

    @SuppressLint("ClickableViewAccessibility")
    private SolegramProfileLinksSheet(BaseFragment fragment, @Nullable String about, @Nullable Utilities.Callback<String> onApply) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.onApply = onApply;
        this.limit = MessagesController.getInstance(account).getAboutLimit();
        final Context context = fragment.getParentActivity();
        fixNavigationBar();
        setApplyBottomPadding(false);

        // while the music is shared, the bio holds the song: the user's own bio is the one to edit
        final String own = SolegramNowPlayingBio.ownBio(UserConfig.getInstance(account).getClientUserId(), about == null ? "" : about);
        initialAbout = own == null ? "" : own;
        final SolegramProfileLinks.Parsed parsed = SolegramProfileLinks.parse(initialAbout);
        links.addAll(parsed.removed);
        row = SolegramProfileLinks.looksLikeRow(initialAbout) && !parsed.removed.isEmpty();

        final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        final int grayColor = getThemedColor(Theme.key_dialogTextGray3);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        final ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // the button stays on screen, also with the keyboard open
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.66f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(20), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // header
        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(PluginSecuritySheet.tileIcon(context, R.drawable.sg_brand_link, BLUE, 42), LayoutHelper.createLinear(42, 42));
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        final TextView heading = text(context, getString(R.string.SG_ProfileLinks), 19, textColor);
        heading.setTypeface(AndroidUtilities.bold());
        titles.addView(heading);
        titles.addView(text(context, getString(R.string.SG_ProfileLinksSub), 13.5f, grayColor), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        head.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        content.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

        // preview: the profile in Solegram (buttons) and in Telegram (text), and the characters left
        final LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setBackground(Theme.createRoundRectDrawable(dp(18), ColorUtils.setAlphaComponent(textColor, 0x0B)));
        preview.setPadding(0, dp(10), 0, dp(12));
        preview.addView(caption(context, getString(R.string.SG_ProfileLinksInSolegram)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 0));
        chipsPreview = new SolegramProfileLinksView(context, resourcesProvider);
        chipsPreview.setColorKeys(Theme.key_dialogBackground, Theme.key_dialogTextBlack);
        preview.addView(chipsPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -2, 0, -2, 0));
        emptyText = text(context, getString(R.string.SG_ProfileLinksEmpty), 14, grayColor);
        emptyText.setPadding(dp(14), dp(6), dp(14), dp(6));
        preview.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final View divider = new View(context);
        divider.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 0x14));
        preview.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 14, 6, 14, 10));
        final LinearLayout tgHead = new LinearLayout(context);
        tgHead.setOrientation(LinearLayout.HORIZONTAL);
        tgHead.addView(caption(context, getString(R.string.SG_ProfileLinksInTelegram)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        counter = text(context, "", 13, grayColor);
        counter.setTypeface(AndroidUtilities.bold());
        tgHead.addView(counter, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        preview.addView(tgHead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 0));
        telegramPreview = text(context, "", 15, textColor);
        telegramPreview.setLineSpacing(dp(2), 1f);
        preview.addView(telegramPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 0));
        warning = text(context, "", 13, getThemedColor(Theme.key_text_RedRegular));
        warning.setVisibility(View.GONE);
        preview.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 8, 14, 0));
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // the bio's own words
        content.addView(sectionHeader(context, getString(R.string.SG_ProfileLinksText)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 8));
        textField = field(context, 16, textColor, accent, getString(R.string.SG_ProfileLinksTextHint));
        textField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textField.setMaxLines(4);
        textField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.max(limit, 70))});
        textField.setText(parsed.text);
        textField.setBackground(Theme.createRoundRectDrawable(dp(14), ColorUtils.setAlphaComponent(textColor, 0x0B)));
        textField.setPadding(dp(14), dp(11), dp(14), dp(11));
        textField.addTextChangedListener(watcher(this::refresh));
        content.addView(textField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // the links
        content.addView(sectionHeader(context, getString(R.string.SG_ProfileLinksList)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 4));
        list = new LinksList(context);
        content.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));

        addButton = text(context, getString(R.string.SG_ProfileLinksAdd), 15, accent);
        addButton.setTypeface(AndroidUtilities.bold());
        addButton.setGravity(Gravity.CENTER_VERTICAL);
        final Drawable plus = ContextCompat.getDrawable(context, R.drawable.msg_add);
        if (plus != null) {
            plus.mutate().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            plus.setBounds(0, 0, dp(24), dp(24));
            addButton.setCompoundDrawables(plus, null, null, null);
        }
        addButton.setCompoundDrawablePadding(dp(16));
        addButton.setPadding(dp(18), 0, dp(18), 0);
        addButton.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 14, 14));
        addButton.setOnClickListener(v -> showGrid());
        content.addView(addButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 8, 0, 8, 0));

        // the platforms
        gridPanel = new LinearLayout(context);
        gridPanel.setOrientation(LinearLayout.VERTICAL);
        gridPanel.setVisibility(View.GONE);
        final LinearLayout gridHead = new LinearLayout(context);
        gridHead.setOrientation(LinearLayout.HORIZONTAL);
        gridHead.setGravity(Gravity.CENTER_VERTICAL);
        gridHead.addView(sectionHeader(context, getString(R.string.SG_ProfileLinksPick)), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        gridHead.addView(iconButton(context, R.drawable.ic_close_white, grayColor, v -> hidePanels()), LayoutHelper.createLinear(36, 36));
        gridPanel.addView(gridHead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 6));
        final int columns = 4;
        LinearLayout gridRow = null;
        final SolegramProfileLinks.Platform[] platforms = SolegramProfileLinks.PLATFORMS;
        for (int i = 0; i < platforms.length; i++) {
            if (i % columns == 0) {
                gridRow = new LinearLayout(context);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                gridPanel.addView(gridRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 82));
            }
            final SolegramProfileLinks.Platform p = platforms[i];
            final PlatformTile tile = new PlatformTile(context, p);
            tile.setOnClickListener(v -> showInput(p, null, -1));
            gridRow.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        if (platforms.length % columns != 0) {
            for (int i = platforms.length % columns; i < columns; i++) {
                gridRow.addView(new View(context), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            }
        }
        content.addView(gridPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        // a username for the chosen platform
        inputPanel = new LinearLayout(context);
        inputPanel.setOrientation(LinearLayout.VERTICAL);
        inputPanel.setVisibility(View.GONE);
        inputPanel.setBackground(Theme.createRoundRectDrawable(dp(18), ColorUtils.setAlphaComponent(textColor, 0x0B)));
        inputPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
        final LinearLayout inputHead = new LinearLayout(context);
        inputHead.setOrientation(LinearLayout.HORIZONTAL);
        inputHead.setGravity(Gravity.CENTER_VERTICAL);
        inputIcon = new ImageView(context);
        inputIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        inputIcon.setPadding(dp(7), dp(7), dp(7), dp(7));
        inputHead.addView(inputIcon, LayoutHelper.createLinear(32, 32));
        inputTitle = text(context, "", 16, textColor);
        inputTitle.setTypeface(AndroidUtilities.bold());
        inputHead.addView(inputTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        inputHead.addView(iconButton(context, R.drawable.ic_close_white, grayColor, v -> hidePanels()), LayoutHelper.createLinear(36, 36));
        inputPanel.addView(inputHead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final LinearLayout inputLine = new LinearLayout(context);
        inputLine.setOrientation(LinearLayout.HORIZONTAL);
        inputLine.setGravity(Gravity.CENTER_VERTICAL);
        inputLine.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_dialogBackground)));
        inputLine.setPadding(dp(12), 0, dp(12), 0);
        inputPrefix = text(context, "", 16, grayColor);
        inputPrefix.setSingleLine(true);
        inputLine.addView(inputPrefix, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        inputField = field(context, 16, textColor, accent, getString(R.string.SG_ProfileLinksUsername));
        inputField.setSingleLine(true);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        inputField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        inputField.setPadding(0, dp(12), 0, dp(12));
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInput();
                return true;
            }
            return false;
        });
        inputField.addTextChangedListener(watcher(this::hideInputError));
        inputLine.addView(inputField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        inputPanel.addView(inputLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        inputError = text(context, "", 13, getThemedColor(Theme.key_text_RedRegular));
        inputError.setVisibility(View.GONE);
        inputPanel.addView(inputError, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
        inputButton = new ButtonWithCounterView(context, resourcesProvider);
        inputButton.setText(getString(R.string.SG_ProfileLinksAddButton), false);
        inputButton.setOnClickListener(v -> commitInput());
        inputPanel.addView(inputButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));
        content.addView(inputPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        // how the links stand: one per line (1 character between them) or in a row (3)
        content.addView(sectionHeader(context, getString(R.string.SG_ProfileLinksLayout)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 8));
        final LinearLayout choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        columnChoice = new Choice(context, getString(R.string.SG_ProfileLinksColumn));
        columnChoice.setOnClickListener(v -> setRow(false));
        rowChoice = new Choice(context, getString(R.string.SG_ProfileLinksRow));
        rowChoice.setOnClickListener(v -> setRow(true));
        choices.addView(columnChoice, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        choices.addView(rowChoice, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 8, 0, 0, 0));
        content.addView(choices, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 16, 0, 16, 0));
        content.addView(text(context, getString(R.string.SG_ProfileLinksLayoutInfo), 13, grayColor), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

        saveButton = new ButtonWithCounterView(context, resourcesProvider);
        saveButton.setText(getString(onApply != null ? R.string.SG_ProfileLinksApply : R.string.SG_ProfileLinksSave), false);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 16));

        setCustomView(root);
        solegramReadingGlass();

        columnChoice.setChosen(!row);
        rowChoice.setChosen(row);
        list.rebuild();
        refresh();
    }

    // region State

    private String composed() {
        return SolegramProfileLinks.compose(textField.getText().toString(), links, row);
    }

    private void refresh() {
        final String bio = composed();
        final int used = SolegramProfileLinks.length(bio);
        final int left = limit - used;
        counter.setText(LocaleController.formatString(R.string.SG_ProfileLinksCounter, used, limit));
        counter.setTextColor(left < 0 ? getThemedColor(Theme.key_text_RedRegular)
            : left <= 5 ? 0xFFFF9500 : getThemedColor(Theme.key_dialogTextGray3));
        telegramPreview.setText(TextUtils.isEmpty(bio) ? "—" : bio);
        // the buttons show what Solegram will read back from this bio, so a link it would not see does not look saved
        final SolegramProfileLinks.Parsed back = SolegramProfileLinks.parse(bio);
        chipsPreview.setLinks(back.links, false);
        chipsPreview.setVisibility(back.links.isEmpty() ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(back.links.isEmpty() ? View.VISIBLE : View.GONE);
        if (left < 0) {
            final boolean premiumWouldFit = !UserConfig.getInstance(account).isPremium()
                && used <= MessagesController.getInstance(account).aboutLengthLimitPremium;
            String w = LocaleController.formatString(R.string.SG_ProfileLinksTooLong, -left);
            if (premiumWouldFit) {
                w += "\n" + LocaleController.formatString(R.string.SG_ProfileLinksPremiumHint, MessagesController.getInstance(account).aboutLengthLimitPremium);
            }
            warning.setText(w);
            warning.setVisibility(View.VISIBLE);
        } else {
            warning.setVisibility(View.GONE);
        }
        saveButton.setEnabled(!saving && left >= 0 && !TextUtils.equals(bio.trim(), initialAbout.trim()));
    }

    private void setRow(boolean value) {
        if (row == value) {
            return;
        }
        row = value;
        columnChoice.setChosen(!value);
        rowChoice.setChosen(value);
        refresh();
    }

    private void removeLink(int index) {
        if (index < 0 || index >= links.size()) {
            return;
        }
        beginTransition();
        links.remove(index);
        if (editingIndex == index) {
            hidePanels();
        } else if (editingIndex > index) {
            editingIndex--;
        }
        list.rebuild();
        refresh();
    }

    private void moveLink(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= links.size() || to >= links.size()) {
            return;
        }
        links.add(to, links.remove(from));
        if (editingIndex == from) {
            editingIndex = to;
        }
        list.rebuild();
        refresh();
    }
    // endregion

    // region Adding

    private void beginTransition() {
        if (!SolegramConfig.animateLists || LiteMode.isPowerSaverApplied()) {
            return; // list animations off, or the battery saver: the change just happens
        }
        final AutoTransition t = new AutoTransition();
        t.setDuration(220);
        t.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        TransitionManager.beginDelayedTransition(content, t);
    }

    private void showGrid() {
        beginTransition();
        editingIndex = -1;
        addButton.setVisibility(View.GONE);
        inputPanel.setVisibility(View.GONE);
        gridPanel.setVisibility(View.VISIBLE);
        AndroidUtilities.hideKeyboard(textField);
    }

    private void hidePanels() {
        beginTransition();
        editingIndex = -1;
        gridPanel.setVisibility(View.GONE);
        inputPanel.setVisibility(View.GONE);
        addButton.setVisibility(View.VISIBLE);
        AndroidUtilities.hideKeyboard(inputField);
    }

    private void showInput(SolegramProfileLinks.Platform platform, @Nullable SolegramProfileLinks.Link editing, int index) {
        beginTransition();
        inputPlatform = platform;
        editingIndex = index;
        gridPanel.setVisibility(View.GONE);
        addButton.setVisibility(View.GONE);
        inputPanel.setVisibility(View.VISIBLE);
        inputIcon.setImageDrawable(markDrawable(getContext(), platform));
        inputIcon.setBackground(circle(platform));
        inputTitle.setText(platform.isWebsite() ? getString(R.string.SG_ProfileLinksWebsite) : platform.name);
        inputPrefix.setText(platform.prefix);
        inputPrefix.setVisibility(TextUtils.isEmpty(platform.prefix) ? View.GONE : View.VISIBLE);
        inputField.setHintText(getString(platform.isWebsite() ? R.string.SG_ProfileLinksWebsiteHint : R.string.SG_ProfileLinksUsername));
        String value = "";
        if (editing != null) {
            value = editing.platform.isWebsite() ? editing.text : editing.text.startsWith(platform.prefix) ? editing.text.substring(platform.prefix.length()) : editing.text;
        }
        inputField.setText(value);
        inputField.setSelection(inputField.length());
        inputError.setVisibility(View.GONE);
        inputButton.setText(getString(index >= 0 ? R.string.SG_ProfileLinksUpdateButton : R.string.SG_ProfileLinksAddButton), false);
        inputField.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(inputField), 120);
    }

    private void hideInputError() {
        if (inputError != null) {
            inputError.setVisibility(View.GONE);
        }
    }

    private void commitInput() {
        if (inputPlatform == null) {
            return;
        }
        final SolegramProfileLinks.Link link = SolegramProfileLinks.build(inputPlatform, inputField.getText().toString());
        if (link == null) {
            inputError.setText(inputPlatform.isWebsite() ? getString(R.string.SG_ProfileLinksInvalidSite)
                : LocaleController.formatString(R.string.SG_ProfileLinksInvalid, inputPlatform.name));
            inputError.setVisibility(View.VISIBLE);
            AndroidUtilities.shakeViewSpring(inputField, -6);
            inputField.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            if (i != editingIndex && links.get(i).text.equalsIgnoreCase(link.text)) {
                inputError.setText(getString(R.string.SG_ProfileLinksDuplicate));
                inputError.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeViewSpring(inputField, -6);
                return;
            }
        }
        beginTransition();
        if (editingIndex >= 0 && editingIndex < links.size()) {
            links.set(editingIndex, link);
        } else {
            links.add(link);
        }
        list.rebuild();
        hidePanels();
        refresh();
    }
    // endregion

    // region Saving

    private void save() {
        if (saving) {
            return;
        }
        final String bio = composed().trim();
        if (SolegramProfileLinks.length(bio) > limit) {
            AndroidUtilities.shakeViewSpring(counter, -6);
            return;
        }
        if (onApply != null) {
            onApply.run(bio);
            dismiss();
            return;
        }
        saving = true;
        saveButton.setLoading(true);
        final TL_account.updateProfile req = new TL_account.updateProfile();
        req.about = bio;
        req.flags |= 4;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            saving = false;
            saveButton.setLoading(false);
            if (error != null) {
                FileLog.e("solegram profile links: " + error.text);
                refresh();
                warning.setText("ABOUT_TOO_LONG".equals(error.text) ? getString(R.string.SG_ProfileLinksTooLongServer) : error.text);
                warning.setVisibility(View.VISIBLE);
                return;
            }
            final long self = UserConfig.getInstance(account).getClientUserId();
            final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(self);
            if (full != null) {
                full.about = bio;
                if (TextUtils.isEmpty(bio)) {
                    full.flags &= ~2;
                } else {
                    full.flags |= 2;
                }
                MessagesStorage.getInstance(account).updateUserInfo(full, false);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, full.id, full);
            }
            dismiss();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_ProfileLinksSaved)).show();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }
    // endregion

    // region Views

    private EditTextBoldCursor field(Context context, float size, int color, int accent, String hint) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setBackground(null);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        field.setTextColor(color);
        field.setHintText(hint);
        field.setHintColor(getThemedColor(Theme.key_dialogTextHint));
        field.setCursorColor(accent);
        field.setCursorSize(dp(size + 3));
        field.setCursorWidth(1.5f);
        field.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        return field;
    }

    private static TextWatcher watcher(Runnable changed) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                changed.run();
            }
        };
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

    private TextView caption(Context context, String value) {
        final TextView view = text(context, value.toUpperCase(), 11.5f, getThemedColor(Theme.key_dialogTextGray3));
        view.setTypeface(AndroidUtilities.bold());
        view.setLetterSpacing(0.04f);
        return view;
    }

    private ImageView iconButton(Context context, int icon, int color, View.OnClickListener click) {
        final ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        view.setOnClickListener(click);
        return view;
    }

    /** The platform's mark, white (dark on a light brand colour), to lie on {@link #circle}. */
    static Drawable markDrawable(Context context, SolegramProfileLinks.Platform p) {
        final Drawable d = ContextCompat.getDrawable(context, p.icon);
        if (d == null) {
            return null;
        }
        final Drawable m = d.mutate();
        m.setColorFilter(new PorterDuffColorFilter(ColorUtils.calculateLuminance(p.color) > 0.6 ? 0xFF000000 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        return m;
    }

    private Drawable circle(SolegramProfileLinks.Platform p) {
        int color = p.isWebsite() ? getThemedColor(Theme.key_featuredStickers_addButton) : p.color;
        if (ColorUtils.calculateLuminance(getThemedColor(Theme.key_dialogBackground)) < 0.5 && ColorUtils.calculateLuminance(color) < 0.03) {
            color = ColorUtils.blendARGB(color, 0xFFFFFFFF, 0.16f); // black marks stay visible on a dark sheet
        }
        final GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** A platform in the grid: its mark in a brand circle and its name. */
    private class PlatformTile extends LinearLayout {

        PlatformTile(Context context, SolegramProfileLinks.Platform p) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            final ImageView mark = new ImageView(context);
            mark.setImageDrawable(markDrawable(context, p));
            mark.setBackground(circle(p));
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setPadding(dp(12), dp(12), dp(12), dp(12));
            addView(mark, LayoutHelper.createLinear(46, 46, Gravity.CENTER_HORIZONTAL));
            final TextView name = text(context, p.isWebsite() ? getString(R.string.SG_ProfileLinksWebsite) : p.name, 12, getThemedColor(Theme.key_dialogTextBlack));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
            setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 14, 14));
            setContentDescription(name.getText());
            ScaleStateListAnimator.apply(this, .06f, 1.4f);
        }
    }

    /** A rounded choice: a soft fill, the accent fill and outline when chosen. */
    private class Choice extends TextView {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final int textColor = getThemedColor(Theme.key_dialogTextBlack);
        private final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        private boolean chosen;

        Choice(Context context, String label) {
            super(context);
            setText(label);
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            setTypeface(AndroidUtilities.bold());
            setGravity(Gravity.CENTER);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            setForeground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 14, 14));
            ScaleStateListAnimator.apply(this, .04f, 1.2f);
        }

        void setChosen(boolean value) {
            chosen = value;
            setTextColor(value ? accent : textColor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float inset = dp(0.75f);
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            final float r = dp(14);
            fill.setColor(chosen ? ColorUtils.setAlphaComponent(accent, 0x24) : ColorUtils.setAlphaComponent(textColor, 0x0D));
            canvas.drawRoundRect(rect, r, r, fill);
            if (chosen) {
                stroke.setColor(accent);
                canvas.drawRoundRect(rect, r, r, stroke);
            }
            super.onDraw(canvas);
        }
    }

    /**
     * The links, one row each: the mark, the platform and the address, remove, and a handle to drag the row
     * up or down (the others make way as it passes them).
     */
    private class LinksList extends LinearLayout {

        private View dragged;
        private int dragFrom = -1, dragTo = -1;
        private float dragStartY;

        LinksList(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        void rebuild() {
            removeAllViews();
            for (int i = 0; i < links.size(); i++) {
                addView(makeRow(i), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, ROW_HEIGHT));
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private View makeRow(int index) {
            final Context context = getContext();
            final SolegramProfileLinks.Link link = links.get(index);
            final int textColor = getThemedColor(Theme.key_dialogTextBlack);
            final int grayColor = getThemedColor(Theme.key_dialogTextGray3);
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(2), 0);
            row.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 14, 14));

            final ImageView mark = new ImageView(context);
            mark.setImageDrawable(markDrawable(context, link.platform));
            mark.setBackground(circle(link.platform));
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setPadding(dp(9), dp(9), dp(9), dp(9));
            row.addView(mark, LayoutHelper.createLinear(36, 36));

            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            final TextView name = text(context, link.platform.isWebsite() ? getString(R.string.SG_ProfileLinksWebsite) : link.platform.name, 15, textColor);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine(true);
            texts.addView(name);
            final TextView address = text(context, link.text, 13, grayColor);
            address.setSingleLine(true);
            address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            texts.addView(address, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 4, 0));

            final ImageView remove = iconButton(context, R.drawable.msg_delete, grayColor, v -> removeLink(indexOfChild(row)));
            remove.setContentDescription(getString(R.string.Delete));
            row.addView(remove, LayoutHelper.createLinear(40, 40));

            final ImageView handle = iconButton(context, R.drawable.list_reorder, grayColor, null);
            handle.setClickable(false);
            handle.setContentDescription(getString(R.string.SG_ProfileLinksReorder));
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && links.size() > 1) {
                    startDrag(row, e.getRawY());
                    return true;
                }
                return false;
            });
            row.addView(handle, LayoutHelper.createLinear(40, 40));

            row.setOnClickListener(v -> {
                final int i = indexOfChild(row);
                if (i >= 0 && i < links.size()) {
                    showInput(links.get(i).platform, links.get(i), i);
                }
            });
            return row;
        }

        private void startDrag(View row, float rawY) {
            dragged = row;
            dragFrom = dragTo = indexOfChild(row);
            dragStartY = rawY;
            row.animate().cancel();
            row.animate().scaleX(1.02f).scaleY(1.02f).translationZ(dp(6)).setDuration(150).start();
            row.setBackground(Theme.createRoundRectDrawable(dp(14), getThemedColor(Theme.key_dialogBackground)));
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return dragged != null || super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (dragged == null) {
                return super.dispatchTouchEvent(ev);
            }
            final int h = dragged.getHeight();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    final float dy = ev.getRawY() - dragStartY;
                    final float minDy = -dragFrom * h, maxDy = (getChildCount() - 1 - dragFrom) * h;
                    dragged.setTranslationY(Math.max(minDy, Math.min(maxDy, dy)));
                    final int to = Math.max(0, Math.min(getChildCount() - 1, dragFrom + Math.round(dragged.getTranslationY() / h)));
                    if (to != dragTo) {
                        dragTo = to;
                        dragged.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        for (int i = 0; i < getChildCount(); i++) {
                            final View child = getChildAt(i);
                            if (child == dragged) {
                                continue;
                            }
                            float shift = 0;
                            if (i > dragFrom && i <= dragTo) {
                                shift = -h;
                            } else if (i < dragFrom && i >= dragTo) {
                                shift = h;
                            }
                            if (child.getTranslationY() != shift) {
                                child.animate().cancel();
                                child.animate().translationY(shift).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                            }
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final int from = dragFrom, to = dragTo;
                    final View row = dragged;
                    dragged = null;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    final MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel); // the handle that started it lets go
                    cancel.recycle();
                    // the row settles into its new place, then the list is laid out in the new order
                    row.animate().cancel();
                    row.animate().translationY((to - from) * (float) h).scaleX(1f).scaleY(1f).translationZ(0).setDuration(160)
                        .withEndAction(() -> {
                            for (int i = 0; i < getChildCount(); i++) {
                                getChildAt(i).animate().cancel();
                                getChildAt(i).setTranslationY(0);
                            }
                            if (from != to) {
                                moveLink(from, to);
                            } else {
                                rebuild();
                            }
                        }).start();
                    return true;
                }
            }
            return true;
        }
    }
    // endregion

    @Override
    public void dismiss() {
        AndroidUtilities.hideKeyboard(textField);
        super.dismiss();
    }

    // dragging a link down must not pull the sheet down with it
    @Override
    protected boolean canDismissWithSwipe() {
        return list == null || list.dragged == null;
    }
}
