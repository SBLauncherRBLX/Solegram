/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramScamGuard;
import com.solegram.messenger.SolegramScamRules;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ReportBottomSheet;

import java.util.List;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * "Possibly a scammer" at the top of a private chat with a risky stranger: the main reasons, and Block, Report or
 * "I know them" (no more warnings about that person). It sits in the chat's glass top panel, so it is calm glass
 * like the panels Telegram puts there. Never blocks anyone by itself.
 */
@SuppressLint("ViewConstructor")
public class SolegramScamBanner extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int[] ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] RED = {0xFFFF3B30, 0xFFFF453A};

    private final BaseFragment fragment;
    private final int account;
    private final long userId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Utilities.Callback0Return<List<MessageObject>> messages;
    private final Utilities.Callback<Boolean> onVisible;

    private final View icon;
    private final TextView title;
    private final TextView reasons;
    private final TextView block, report, known;
    private int shownLevel = -1;
    private boolean wantVisible;

    private final Runnable update = this::update;

    /**
     * @param messages  the chat's loaded messages (newest first)
     * @param onVisible asked to show (true) or hide the banner, animated
     */
    public SolegramScamBanner(Context context, BaseFragment fragment, long userId, Theme.ResourcesProvider resourcesProvider,
                              Utilities.Callback0Return<List<MessageObject>> messages, Utilities.Callback<Boolean> onVisible) {
        super(context);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.userId = userId;
        this.resourcesProvider = resourcesProvider;
        this.messages = messages;
        this.onVisible = onVisible;
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(10), dp(12), dp(10));

        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        icon = PluginSecuritySheet.tileIcon(context, R.drawable.msg_warning, ORANGE, 34);
        top.addView(icon, LayoutHelper.createLinear(34, 34, Gravity.TOP, 0, 1, 10, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        reasons = new TextView(context);
        reasons.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reasons.setMaxLines(3);
        reasons.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(reasons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        top.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        block = pill(context);
        report = pill(context);
        known = pill(context);
        block.setText(getString(R.string.SG_ScamBlock));
        report.setText(getString(R.string.SG_ScamReport));
        known.setText(getString(R.string.SG_ScamKnown));
        buttons.addView(block, LayoutHelper.createLinear(0, 32, 1f, 0, 0, 6, 0));
        buttons.addView(report, LayoutHelper.createLinear(0, 32, 1f, 0, 0, 6, 0));
        buttons.addView(known, LayoutHelper.createLinear(0, 32, 1f));
        addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 0, 10, 0, 0));

        block.setOnClickListener(v -> {
            final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            if (user == null) {
                return;
            }
            AlertsCreator.showBlockReportSpamAlert(fragment, userId, user, null, null, false, null, param -> {
                if (param == 1) {
                    fragment.finishFragment();
                } else {
                    update();
                }
            }, resourcesProvider);
        });
        report.setOnClickListener(v -> ReportBottomSheet.openChat(fragment, userId));
        known.setOnClickListener(v -> {
            SolegramScamGuard.markKnown(account, userId);
            setWanted(false);
            final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done,
                LocaleController.formatString(R.string.SG_ScamKnownDone, UserObject.getFirstName(user))).show();
        });
        updateColors();
    }

    private TextView pill(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(4), 0, dp(4), 0);
        return view;
    }

    /** A tinted capsule with a ripple of the same colour. */
    private void stylePill(TextView view, int color) {
        view.setTextColor(color);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Theme.multAlpha(color, 0.12f));
        final android.graphics.drawable.Drawable ripple = Theme.createRadSelectorDrawable(Theme.multAlpha(color, 0.18f), 16, 16);
        view.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, ripple}));
    }

    public void updateColors() {
        final int accent = SolegramScamGuard.levelColor(shownLevel == SolegramScamRules.DANGER ? SolegramScamRules.DANGER : SolegramScamRules.CAUTION);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        reasons.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        stylePill(block, SolegramScamGuard.levelColor(SolegramScamRules.DANGER));
        stylePill(report, accent);
        stylePill(known, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
    }

    /** Checks again (soon, once: several notifications come together). */
    public void scheduleUpdate() {
        AndroidUtilities.cancelRunOnUIThread(update);
        AndroidUtilities.runOnUIThread(update, 250);
    }

    public void update() {
        AndroidUtilities.cancelRunOnUIThread(update);
        @Nullable final List<MessageObject> list = messages != null ? messages.run() : null;
        final SolegramScamGuard.AccountRisk risk = SolegramScamGuard.accountRisk(account, userId, list);
        if (risk == null || risk.level == SolegramScamRules.SAFE) {
            setWanted(false);
            if (risk != null) {
                SolegramScamGuard.autoCheckAccount(account, risk, list, this::scheduleUpdate);
            }
            return;
        }
        shownLevel = risk.level;
        new PluginSecurityTileColors(risk.level == SolegramScamRules.DANGER ? RED : ORANGE).apply(icon);
        updateColors(); // also follows a theme change
        title.setText(getString(R.string.SG_ScamBannerTitle));
        reasons.setText(risk.summary(3));
        setWanted(true);
        SolegramScamGuard.autoCheckAccount(account, risk, list, this::scheduleUpdate);
    }

    private void setWanted(boolean visible) {
        if (wantVisible != visible) {
            wantVisible = visible;
            onVisible.run(visible);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.userInfoDidLoad);
        nc.addObserver(this, NotificationCenter.peerSettingsDidLoad);
        nc.addObserver(this, NotificationCenter.messagesDidLoad);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.addObserver(this, NotificationCenter.contactsDidLoad);
        nc.addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramSettingsChanged);
        scheduleUpdate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(update);
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.userInfoDidLoad);
        nc.removeObserver(this, NotificationCenter.peerSettingsDidLoad);
        nc.removeObserver(this, NotificationCenter.messagesDidLoad);
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.removeObserver(this, NotificationCenter.contactsDidLoad);
        nc.removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramSettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.userInfoDidLoad || id == NotificationCenter.peerSettingsDidLoad) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] != userId) {
                return;
            }
        } else if (id == NotificationCenter.messagesDidLoad || id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] != userId) {
                return;
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            if (args.length > 0 && args[0] instanceof Integer && ((Integer) args[0] & (MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_AVATAR)) == 0) {
                return;
            }
        }
        scheduleUpdate();
    }

    /** Recolours a settings-style tile (made by PluginSecuritySheet.tileIcon). */
    private static final class PluginSecurityTileColors {
        final int[] colors;

        PluginSecurityTileColors(int[] colors) {
            this.colors = colors;
        }

        void apply(View tile) {
            if (tile.getBackground() instanceof org.telegram.ui.SettingsActivity.SettingCell.Background) {
                final int base = PluginSecuritySheet.color(colors);
                ((org.telegram.ui.SettingsActivity.SettingCell.Background) tile.getBackground())
                    .setColor(androidx.core.graphics.ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
                tile.invalidate();
            }
        }
    }

    /** True while the banner shows (for tests of the chat's top panel). */
    public boolean isWanted() {
        return wantVisible && SolegramConfig.scamAccounts;
    }
}
