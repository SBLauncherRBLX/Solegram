/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramSecurity;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginSecurity;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.NotificationsSettingsActivity;
import org.telegram.ui.PasscodeActivity;
import org.telegram.ui.SessionsActivity;
import org.telegram.ui.TwoStepVerificationActivity;
import org.telegram.ui.TwoStepVerificationSetupActivity;

import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

/**
 * Solegram settings → Security: the switches (recent apps, risky files, notification text) and the security
 * checkup, a short list of what protects the account now, each with the screen that changes it.
 */
public class SolegramSecurityActivity extends UniversalFragment {

    private static final int ID_RECENTS = 1;
    private static final int ID_NOTIF_PREVIEW = 2;
    private static final int ID_WARN_FILES = 3;
    private static final int ID_CHECKUP = 4;

    private static final int ID_CHECK_PASSCODE = 11;
    private static final int ID_CHECK_2FA = 12;
    private static final int ID_CHECK_SESSIONS = 13;
    private static final int ID_CHECK_SCAM = 14;
    private static final int ID_CHECK_PLUGINS = 15;

    // Apple HIG system colors (light, dark), as on the other Solegram pages
    private static final int[] TILE_GREEN = {0xFF34C759, 0xFF30D158};
    private static final int[] TILE_ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] TILE_GRAY = {0xFF8E8E93, 0xFF8E8E93};
    private static final int[] TILE_BLUE = {0xFF007AFF, 0xFF0A84FF};
    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};
    private static final int[] TILE_RED = {0xFFFF3B30, 0xFFFF453A};

    private final boolean checkup;

    // checkup: loaded from the server, null until it answered (or when it failed)
    private TL_account.Password password;
    private boolean passwordFailed;
    private int sessions = -1;
    private ScoreView scoreView;

    /** @param checkup the security checkup page, otherwise the Security settings */
    public SolegramSecurityActivity(boolean checkup) {
        this.checkup = checkup;
    }


    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections(); // rounded section cards, as on the other Solegram pages
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // back from the passcode / password / sessions screens: what they changed shows at once
        if (checkup) {
            load(); // the first time too: the rows say "Checking…" until the answers come
            update();
        } else if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(checkup ? R.string.SG_SecCheckup : R.string.SG_Security);
    }

    private void load() {
        final int account = currentAccount;
        getConnectionsManager().sendRequest(new TL_account.getPassword(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TL_account.Password) {
                password = (TL_account.Password) response;
                passwordFailed = false;
            } else {
                passwordFailed = true;
            }
            update();
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
        ConnectionsManager.getInstance(account).sendRequest(new TL_account.getAuthorizations(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TL_account.authorizations) {
                sessions = ((TL_account.authorizations) response).authorizations.size();
            }
            update();
        }));
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (checkup) {
            fillCheckup(items);
            return;
        }
        items.add(UItem.asHeader(getString(R.string.SG_SecScreen)));
        items.add(tile(UItem.asButtonCheck(ID_RECENTS, getString(R.string.SG_SecRecents),
            getString(Build.VERSION.SDK_INT >= 33 ? R.string.SG_SecRecentsInfo : R.string.SG_SecRecentsInfoOld)).setChecked(SolegramConfig.secureRecents), TILE_INDIGO));
        items.add(tile(UItem.asButton(ID_NOTIF_PREVIEW, R.drawable.msg_notifications, getString(R.string.SG_SecNotifPreview),
            getString(previewShown() ? R.string.SG_SecNotifPreviewOn : R.string.SG_SecNotifPreviewOff)), TILE_RED)
            .setSolegramSubtitle(getString(R.string.SG_SecNotifPreviewInfo)));
        items.add(UItem.asShadow(getString(R.string.SG_SecSecretShots)));

        items.add(UItem.asHeader(getString(R.string.SG_SecFiles)));
        items.add(UItem.asButtonCheck(ID_WARN_FILES, getString(R.string.SG_SecWarnFiles), getString(R.string.SG_SecWarnFilesInfo))
            .setChecked(SolegramConfig.warnDangerousFiles));
        items.add(UItem.asShadow(null));

        items.add(tile(UItem.asButton(ID_CHECKUP, R.drawable.outline_shield_check, getString(R.string.SG_SecCheckup)), TILE_GREEN)
            .setSolegramSubtitle(getString(R.string.SG_SecCheckupSub)));
        items.add(UItem.asShadow(null));
    }

    private boolean previewShown() {
        return MessagesController.getNotificationsSettings(currentAccount).getBoolean("EnablePreviewAll", true);
    }

    // region Checkup

    /** 1 passed, 0 to improve, -1 not known yet. */
    private int passcodeState() {
        return SharedConfig.passcodeHash.length() > 0 ? 1 : 0;
    }

    private int passwordState() {
        return password != null ? (password.has_password ? 1 : 0) : -1;
    }

    private int sessionsState() {
        return sessions < 0 ? -1 : 1; // more devices is not wrong in itself: the row only says how many
    }

    private int scamState() {
        return SolegramConfig.scamLinks && SolegramConfig.scamAccounts ? 1 : 0;
    }

    private int riskyPlugins() {
        int risky = 0;
        if (!PluginsController.isEngineAvailable()) {
            return 0;
        }
        for (PluginMeta meta : PluginsController.getInstance().getPlugins()) {
            if (meta.enabled && meta.report != null && meta.report.verdict != PluginSecurity.VERDICT_SAFE) {
                risky++;
            }
        }
        return risky;
    }

    private int pluginsState() {
        return riskyPlugins() == 0 && !SolegramConfig.pluginsDevMode ? 1 : 0;
    }

    private void fillCheckup(ArrayList<UItem> items) {
        final int[] states = {passcodeState(), passwordState(), sessionsState(), scamState(), pluginsState()};
        int passed = 0, known = 0;
        for (int s : states) {
            if (s >= 0) {
                known++;
                passed += s;
            }
        }
        if (scoreView == null) {
            scoreView = new ScoreView(getContext());
        }
        scoreView.set(passed, states.length, known == states.length);
        items.add(UItem.asCustom(scoreView));
        items.add(UItem.asShadow(null));

        items.add(check(ID_CHECK_PASSCODE, R.drawable.msg2_secret, R.string.SG_SecCheckPasscode, states[0],
            getString(states[0] == 1 ? R.string.SG_SecCheckPasscodeOn : R.string.SG_SecCheckPasscodeOff)));
        items.add(check(ID_CHECK_2FA, R.drawable.msg2_permissions, R.string.SG_SecCheck2fa, states[1],
            getString(states[1] == 1 ? R.string.SG_SecCheck2faOn : states[1] == 0 ? R.string.SG_SecCheck2faOff
                : passwordFailed ? R.string.SG_SecCheck2faUnknown : R.string.SG_SecCheckChecking)));
        items.add(check(ID_CHECK_SESSIONS, R.drawable.msg2_devices, R.string.SG_SecCheckSessions, states[2],
            sessions < 0 ? getString(R.string.SG_SecCheckChecking) : sessions <= 1 ? getString(R.string.SG_SecCheckSessionsOne)
                : LocaleController.formatString(R.string.SG_SecCheckSessionsMany, sessions)));
        items.add(check(ID_CHECK_SCAM, R.drawable.outline_shield_plain_24, R.string.SG_SecCheckScam, states[3],
            getString(states[3] == 1 ? R.string.SG_SecCheckScamOn : R.string.SG_SecCheckScamOff)));
        final int loaded = PluginsController.isEngineAvailable() ? PluginsController.getInstance().getLoadedCount() : 0;
        final int risky = riskyPlugins();
        String plugins = loaded == 0 ? getString(R.string.SG_SecCheckPluginsNone)
            : risky > 0 ? LocaleController.formatString(R.string.SG_SecCheckPluginsRisk, loaded, risky)
            : LocaleController.formatString(R.string.SG_SecCheckPluginsOk, loaded);
        if (SolegramConfig.pluginsDevMode) {
            plugins += " " + getString(R.string.SG_SecCheckPluginsDev);
        }
        items.add(check(ID_CHECK_PLUGINS, R.drawable.msg_bots, R.string.SG_SecCheckPlugins, states[4], plugins));
        items.add(UItem.asShadow(getString(R.string.SG_SecCheckTips)));
    }

    private UItem check(int id, int icon, int title, int state, CharSequence detail) {
        final UItem item = UItem.asButton(id, icon, getString(title));
        return tile(item, state == 1 ? TILE_GREEN : state == 0 ? TILE_ORANGE : TILE_GRAY).setSolegramSubtitle(detail);
    }
    // endregion

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_RECENTS: {
                final boolean value = SolegramConfig.toggle("secureRecents");
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                SolegramSecurity.applyToAll();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramSettingsChanged, "secureRecents");
                listView.adapter.update(true);
                return;
            }
            case ID_WARN_FILES: {
                final boolean value = SolegramConfig.toggle("warnDangerousFiles");
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                listView.adapter.update(true);
                return;
            }
            case ID_NOTIF_PREVIEW:
                presentFragment(new NotificationsSettingsActivity());
                return;
            case ID_CHECKUP:
                presentFragment(new SolegramSecurityActivity(true));
                return;
            case ID_CHECK_PASSCODE:
                presentFragment(PasscodeActivity.determineOpenFragment());
                return;
            case ID_CHECK_2FA:
                openPassword();
                return;
            case ID_CHECK_SESSIONS:
                presentFragment(new SessionsActivity(0));
                return;
            case ID_CHECK_SCAM:
                presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_SCAM));
                return;
            case ID_CHECK_PLUGINS:
                presentFragment(new PluginsActivity());
                return;
        }
    }

    /** As Telegram's privacy screen does it: the password screen, or its setup when there is none. */
    private void openPassword() {
        if (password == null) {
            return;
        }
        if (!TwoStepVerificationActivity.canHandleCurrentPassword(password, false)) {
            return;
        }
        if (password.has_password) {
            final TwoStepVerificationActivity fragment = new TwoStepVerificationActivity();
            fragment.setPassword(password);
            presentFragment(fragment);
        } else {
            final int type = TextUtils.isEmpty(password.email_unconfirmed_pattern)
                ? TwoStepVerificationSetupActivity.TYPE_INTRO : TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM;
            presentFragment(new TwoStepVerificationSetupActivity(type, password));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private UItem tile(UItem item, int[] color) {
        final int base = Theme.isCurrentThemeDark() ? color[1] : color[0];
        return item.setIconTile(ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
    }

    /**
     * The top card of the checkup: a ring that fills with the checks passed around a shield, the verdict and the
     * count. Everything is allocated once; the ring eases to a new value (no animation with reduced motion).
     */
    private static final class ScoreView extends View {

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final AnimatedFloat progress = new AnimatedFloat(this, 0, 520, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final Drawable shield;
        private int passed, total = 1;
        private boolean complete;
        private int glowColor;
        private String title = "", subtitle = "";
        private StaticLayout titleLayout, subtitleLayout;
        private int layoutWidth;

        ScoreView(Context context) {
            super(context);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            titlePaint.setTypeface(AndroidUtilities.bold());
            titlePaint.setTextSize(AndroidUtilities.dp(20));
            subtitlePaint.setTextSize(AndroidUtilities.dp(14));
            shield = ContextCompat.getDrawable(context, R.drawable.outline_shield_check).mutate();
        }

        void set(int passed, int total, boolean complete) {
            this.passed = passed;
            this.total = Math.max(1, total);
            this.complete = complete;
            title = getString(!complete ? R.string.SG_SecCheckChecking : passed == total ? R.string.SG_SecCheckAllGood : R.string.SG_SecCheckSomeTips);
            subtitle = LocaleController.formatString(R.string.SG_SecCheckScore, passed, total);
            layoutWidth = 0; // texts are laid out again on the next draw
            if (!org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_CHAT_SCALE)) {
                progress.set(passed / (float) this.total, true);
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(196), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final boolean dark = Theme.isCurrentThemeDark();
            final int good = dark ? TILE_GREEN[1] : TILE_GREEN[0];
            final int warn = dark ? TILE_ORANGE[1] : TILE_ORANGE[0];
            final int color = !complete ? (dark ? TILE_GRAY[1] : TILE_GRAY[0]) : passed == total ? good : warn;
            final int width = getWidth();

            // the soft color wash of the card and the shield tint change only with the verdict color
            if (glowColor != color) {
                glowColor = color;
                shield.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
                glow.setShader(new LinearGradient(0, 0, 0, AndroidUtilities.dp(196),
                    ColorUtils.setAlphaComponent(color, dark ? 46 : 34), ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, width, getHeight(), glow);

            final float cx = width / 2f, cy = AndroidUtilities.dp(62);
            final float r = AndroidUtilities.dp(38);
            final float stroke = AndroidUtilities.dp(7);
            track.setStrokeWidth(stroke);
            ring.setStrokeWidth(stroke);
            track.setColor(ColorUtils.setAlphaComponent(color, 48));
            ring.setColor(color);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawCircle(cx, cy, r, track);
            final float value = progress.set(passed / (float) total);
            if (value > 0.001f) {
                canvas.drawArc(rect, -90, 360 * value, false, ring);
            }
            final int icon = AndroidUtilities.dp(34);
            shield.setBounds((int) (cx - icon / 2f), (int) (cy - icon / 2f), (int) (cx + icon / 2f), (int) (cy + icon / 2f));
            shield.draw(canvas);

            final int textWidth = Math.max(1, width - AndroidUtilities.dp(48));
            if (layoutWidth != textWidth || titleLayout == null) {
                layoutWidth = textWidth;
                titleLayout = new StaticLayout(title, titlePaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0, false);
                subtitleLayout = new StaticLayout(subtitle, subtitlePaint, textWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0, false);
            }
            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            canvas.save();
            canvas.translate(AndroidUtilities.dp(24), cy + r + AndroidUtilities.dp(18));
            titleLayout.draw(canvas);
            canvas.translate(0, titleLayout.getHeight() + AndroidUtilities.dp(4));
            subtitleLayout.draw(canvas);
            canvas.restore();
        }
    }
}
