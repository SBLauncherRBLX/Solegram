/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
 * "Possibly a scammer" at the top of a private chat with a risky stranger: one line, the main reason and "Details".
 * The details (all the reasons, Block, Report, "Not a scammer") open in the warning dialog. It sits in the chat's glass
 * top panel, so it is calm glass like the panels Telegram puts there. Never blocks anyone by itself.
 */
@SuppressLint("ViewConstructor")
public class SolegramScamBanner extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final BaseFragment fragment;
    private final int account;
    private final long userId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Utilities.Callback0Return<List<MessageObject>> messages;
    private final Utilities.Callback<Boolean> onVisible;

    private final View icon;
    private final TextView title;
    private final TextView reason;
    private final TextView more;
    private int shownLevel = -1;
    private boolean wantVisible;
    @Nullable
    private SolegramScamGuard.AccountRisk risk;

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
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), dp(8), dp(10), dp(8));

        icon = PluginSecuritySheet.tileIcon(context, R.drawable.msg_warning, SolegramScamGuard.levelTileColors(SolegramScamRules.CAUTION), 30);
        addView(icon, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        reason = new TextView(context);
        reason.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        reason.setSingleLine(true);
        reason.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(reason, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        more = new TextView(context);
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        more.setTypeface(AndroidUtilities.bold());
        more.setGravity(Gravity.CENTER);
        more.setSingleLine(true);
        more.setPadding(dp(12), 0, dp(12), 0);
        more.setText(getString(R.string.SG_ScamMore));
        addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        more.setOnClickListener(v -> showDetails());
        setOnClickListener(v -> showDetails());
        SolegramMotion.pressable(more, SolegramMotion.PRESS_BUTTON);
        updateColors();
    }

    /** All the reasons and the answers, in the warning dialog. */
    private void showDetails() {
        final SolegramScamGuard.AccountRisk r = risk;
        if (r == null) {
            return;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        SolegramScamDialog.showAccount(fragment, r, user != null ? UserObject.getUserName(user) : null, () -> {
            final TLRPC.User u = MessagesController.getInstance(account).getUser(userId);
            if (u == null) {
                return;
            }
            AlertsCreator.showBlockReportSpamAlert(fragment, userId, u, null, null, false, null, param -> {
                if (param == 1) {
                    fragment.finishFragment();
                } else {
                    update();
                }
            }, resourcesProvider);
        }, () -> ReportBottomSheet.openChat(fragment, userId), () -> {
            SolegramScamGuard.markKnown(account, userId);
            setWanted(false);
            final TLRPC.User u = MessagesController.getInstance(account).getUser(userId);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done,
                LocaleController.formatString(R.string.SG_ScamKnownDone, UserObject.getFirstName(u))).show();
        });
    }

    public void updateColors() {
        final int accent = SolegramScamGuard.levelColor(shownLevel == SolegramScamRules.DANGER ? SolegramScamRules.DANGER : SolegramScamRules.CAUTION);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        reason.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        // a tinted capsule with a ripple of the same colour
        more.setTextColor(accent);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(Theme.multAlpha(accent, 0.13f));
        final Drawable ripple = Theme.createRadSelectorDrawable(Theme.multAlpha(accent, 0.18f), 15, 15);
        more.setBackground(new LayerDrawable(new Drawable[]{bg, ripple}));
        setBackground(Theme.getSelectorDrawable(Theme.multAlpha(accent, 0.08f), false));
    }

    /** Checks again (soon, once: several notifications come together). */
    public void scheduleUpdate() {
        AndroidUtilities.cancelRunOnUIThread(update);
        AndroidUtilities.runOnUIThread(update, 250);
    }

    public void update() {
        AndroidUtilities.cancelRunOnUIThread(update);
        @Nullable final List<MessageObject> list = messages != null ? messages.run() : null;
        final SolegramScamGuard.AccountRisk r = SolegramScamGuard.accountRisk(account, userId, list);
        risk = r;
        if (r == null || r.level == SolegramScamRules.SAFE) {
            setWanted(false);
            if (r != null) {
                SolegramScamGuard.autoCheckAccount(account, r, list, this::scheduleUpdate);
            }
            return;
        }
        if (shownLevel != r.level) {
            shownLevel = r.level;
            new TileColors(SolegramScamGuard.levelTileColors(r.level)).apply(icon);
        }
        updateColors(); // also follows a theme change
        title.setText(getString(r.level == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamBannerTitle));
        reason.setText(r.mainReason());
        setWanted(true);
        SolegramScamGuard.autoCheckAccount(account, r, list, this::scheduleUpdate);
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
    private static final class TileColors {
        final int[] colors;

        TileColors(int[] colors) {
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
