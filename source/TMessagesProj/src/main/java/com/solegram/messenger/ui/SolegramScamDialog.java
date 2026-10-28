/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;
import com.solegram.messenger.SolegramScamGuard;
import com.solegram.messenger.SolegramScamRules;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * The warning before a suspicious link opens, and the details of a suspicious stranger. One calm layout on the
 * dialog's reading glass: an icon and a verdict capsule, the real site in big letters with the deceiving part in
 * colour, a short "what looks wrong" card, one line of advice, then the answers: "Don't open" as the main one,
 * "Check with AI" and "Open anyway" beside each other, and a quiet "Not a scam" that trusts the site for good.
 * Amber says "be careful", red only "this is a scam".
 */
public final class SolegramScamDialog {

    private static final int MAX_REASONS = 5;

    private SolegramScamDialog() {
    }

    // region Link

    /** @param open opens the link (after "Open anyway" or "Not a scam") */
    public static void show(BaseFragment fragment, SolegramScamGuard.Link link, Runnable open) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final SolegramScamRules.LinkReport report = link.report;
        final int level = Math.max(SolegramScamRules.CAUTION, link.level());
        final int accent = SolegramScamGuard.levelColor(level);
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
        final int grayColor = Theme.getColor(Theme.key_dialogTextGray3, rp);
        final int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        final AlertDialog[] dialog = new AlertDialog[1];

        final LinearLayout root = column(context);
        root.setPadding(dp(20), dp(22), dp(20), dp(12));

        final View icon = PluginSecuritySheet.tileIcon(context, R.drawable.msg_warning, SolegramScamGuard.levelTileColors(level), 56);
        root.addView(icon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL));

        final TextView title = text(context, getString(R.string.SG_ScamLinkTitle), 20, textColor);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        final TextView verdict = capsule(context, getString(level == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamLevelCaution), accent);
        root.addView(verdict, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        // the site, as big as a name: what the user is about to trust
        final LinearLayout card = card(context, textColor);
        final TextView domain = text(context, headline(report, accent), 21, textColor);
        domain.setTypeface(AndroidUtilities.bold());
        domain.setGravity(Gravity.CENTER);
        domain.setMaxLines(3);
        domain.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(domain, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final String full = report.url;
        if (!TextUtils.isEmpty(full) && !full.equals(domain.getText().toString())) {
            final TextView url = text(context, full, 12.5f, grayColor);
            url.setGravity(Gravity.CENTER);
            url.setMaxLines(2);
            url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(url, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        if (report.site != null && report.displayHost != null && report.telegramUsername == null && !report.site.equals(report.host)
            && !report.site.equals(report.displayHost)) {
            final TextView owner = text(context, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SG_ScamRealSite, report.site)), 13, grayColor);
            owner.setGravity(Gravity.CENTER);
            card.addView(owner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        // what looks wrong
        final View why = reasonsCard(context, link.reasons(), accent, textColor, grayColor);
        if (why != null) {
            root.addView(why, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }

        final TextView hint = text(context, getString(level == SolegramScamRules.DANGER ? R.string.SG_ScamHintDanger : R.string.SG_ScamHintCaution), 13, grayColor);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(1), 1f);
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 12, 6, 0));

        // what AI said (Gemini Nano may have looked already)
        final TextView ai = text(context, "", 14, textColor);
        ai.setPadding(dp(14), dp(10), dp(14), dp(10));
        ai.setLineSpacing(dp(1), 1f);
        final GradientDrawable aiBg = new GradientDrawable();
        aiBg.setCornerRadius(dp(16));
        aiBg.setColor(Theme.multAlpha(blue, 0.1f));
        ai.setBackground(aiBg);
        ai.setVisibility(View.GONE);
        root.addView(ai, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        if (link.aiLevel >= 0) {
            ai.setText(SolegramScamGuard.verdictLine(link));
            ai.setVisibility(View.VISIBLE);
        }

        // the safe answer is the big one
        final ButtonWithCounterView dontOpen = new ButtonWithCounterView(context, rp);
        dontOpen.setText(getString(R.string.SG_ScamDontOpen), false);
        dontOpen.setOnClickListener(v -> dismiss(dialog));
        root.addView(dontOpen, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView askAi = flatButton(context, getString(R.string.SG_ScamCheckAi), blue);
        final TextView openAnyway = flatButton(context, getString(R.string.SG_ScamOpenAnyway),
            level == SolegramScamRules.DANGER ? Theme.getColor(Theme.key_text_RedBold, rp) : accent);
        row.addView(askAi, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 4, 0));
        row.addView(openAnyway, LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
        root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 0));
        askAi.setOnClickListener(v -> {
            askAi.setEnabled(false);
            SolegramMotion.fadeTo(askAi, 0.6f);
            askAi.setText(getString(R.string.SG_ScamAiChecking));
            SolegramScamGuard.askAi(link, (answer, error) -> {
                askAi.setEnabled(true);
                SolegramMotion.fadeTo(askAi, 1f);
                askAi.setText(getString(R.string.SG_ScamCheckAi));
                if (answer == null && error == null) {
                    return; // declined the online service
                }
                SolegramMotion.beginLayoutTransition(root);
                ai.setText(answer != null ? answer : error);
                ai.setVisibility(View.VISIBLE);
                if (answer != null) {
                    askAi.setVisibility(View.GONE);
                    final int now = link.level();
                    final int shown = Math.max(SolegramScamRules.CAUTION, now);
                    styleCapsule(verdict, now == SolegramScamRules.SAFE ? blue : SolegramScamGuard.levelColor(shown));
                    verdict.setText(getString(now == SolegramScamRules.SAFE ? R.string.SG_ScamAiSafe
                        : shown == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamLevelCaution));
                }
            });
        });
        if (link.aiLevel >= 0) {
            askAi.setVisibility(View.GONE);
        }
        openAnyway.setOnClickListener(v -> {
            dismiss(dialog);
            open.run();
        });

        // "not a scam": the site (or the Telegram account) is trusted from now on, and the link opens
        final boolean canTrustUser = report.telegramUsername != null;
        final boolean canTrustSite = !canTrustUser && report.site != null && !report.telegramService && report.host != null;
        if (canTrustSite || canTrustUser) {
            final TextView notScam = flatButton(context, getString(R.string.SG_ScamNotScam), grayColor);
            notScam.setTypeface(null);
            notScam.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            notScam.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(grayColor, 0.1f), 14, 14));
            notScam.setOnClickListener(v -> {
                final String name;
                if (canTrustUser) {
                    SolegramScamGuard.trustUsername(report.telegramUsername);
                    name = "@" + report.telegramUsername;
                } else {
                    SolegramScamGuard.trustSite(report.site);
                    name = report.site;
                }
                dismiss(dialog);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, canTrustUser
                    ? LocaleController.formatString(R.string.SG_ScamTrustedUserDone, name)
                    : LocaleController.formatString(R.string.SG_ScamTrustedSiteDone, name)).show();
                open.run();
            });
            root.addView(notScam, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 2, 0, 0));
        }

        SolegramMotion.pressable(askAi, SolegramMotion.PRESS_BUTTON);
        SolegramMotion.pressable(openAnyway, SolegramMotion.PRESS_BUTTON);
        present(fragment, context, rp, root, dialog);
    }

    /** The host (or @username) with the deceiving letters in the warning colour. */
    private static CharSequence headline(SolegramScamRules.LinkReport report, int accent) {
        if (report.telegramUsername != null) {
            final SpannableStringBuilder sb = new SpannableStringBuilder("@" + report.telegramUsername);
            if (report.has(SolegramScamRules.R_TG_IMPERSONATION)) {
                sb.setSpan(new ForegroundColorSpan(accent), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return sb;
        }
        if (report.displayHost == null) {
            final String url = report.url;
            final int colon = url.indexOf(':');
            return colon > 0 && colon < 12 ? url.substring(0, colon + 1) : url;
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder(report.displayHost);
        for (int[] range : report.highlight) {
            if (range[0] >= 0 && range[1] <= sb.length() && range[0] < range[1]) {
                sb.setSpan(new ForegroundColorSpan(accent), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new UnderlineSpan(), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    // endregion

    // region Account

    /**
     * Why a stranger looks like a scammer, with Block, Report and "Not a scammer".
     *
     * @param name   the stranger's name, as the chat shows it
     * @param block  asks to block (and report) them
     * @param report opens Telegram's report sheet
     * @param known  the user knows them: no more warnings
     */
    public static void showAccount(BaseFragment fragment, SolegramScamGuard.AccountRisk risk, @Nullable String name, Runnable block, Runnable report, Runnable known) {
        final Context context = fragment.getParentActivity();
        if (context == null || risk == null) {
            return;
        }
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final int level = Math.max(SolegramScamRules.CAUTION, risk.level);
        final int accent = SolegramScamGuard.levelColor(level);
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
        final int grayColor = Theme.getColor(Theme.key_dialogTextGray3, rp);
        final AlertDialog[] dialog = new AlertDialog[1];

        final LinearLayout root = column(context);
        root.setPadding(dp(20), dp(22), dp(20), dp(12));

        final View icon = PluginSecuritySheet.tileIcon(context, R.drawable.msg_warning, SolegramScamGuard.levelTileColors(level), 56);
        root.addView(icon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL));

        final TextView title = text(context, getString(R.string.SG_ScamBannerTitle), 20, textColor);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        if (!TextUtils.isEmpty(name)) {
            final TextView who = text(context, name, 15, grayColor);
            who.setGravity(Gravity.CENTER);
            who.setSingleLine(true);
            who.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(who, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        final TextView verdict = capsule(context, getString(level == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamLevelCaution), accent);
        root.addView(verdict, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        final View why = reasonsCard(context, risk.reasonsToShow(), accent, textColor, grayColor);
        if (why != null) {
            root.addView(why, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));
        }
        final TextView hint = text(context, getString(R.string.SG_ScamAccountHint), 13, grayColor);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(1), 1f);
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 12, 6, 0));

        final ButtonWithCounterView blockButton = new ButtonWithCounterView(context, rp);
        blockButton.setText(getString(R.string.SG_ScamBlock), false);
        blockButton.setOnClickListener(v -> {
            dismiss(dialog);
            block.run();
        });
        root.addView(blockButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView reportButton = flatButton(context, getString(R.string.SG_ScamReport), accent);
        final TextView notScammer = flatButton(context, getString(R.string.SG_ScamNotScammer), grayColor);
        row.addView(reportButton, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 4, 0));
        row.addView(notScammer, LayoutHelper.createLinear(0, 44, 1f, 4, 0, 0, 0));
        root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 0));
        reportButton.setOnClickListener(v -> {
            dismiss(dialog);
            report.run();
        });
        notScammer.setOnClickListener(v -> {
            dismiss(dialog);
            known.run();
        });
        SolegramMotion.pressable(reportButton, SolegramMotion.PRESS_BUTTON);
        SolegramMotion.pressable(notScammer, SolegramMotion.PRESS_BUTTON);
        present(fragment, context, rp, root, dialog);
    }

    // endregion

    // region Parts

    private static void present(BaseFragment fragment, Context context, Theme.ResourcesProvider rp, LinearLayout root, AlertDialog[] dialog) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setView(root);
        builder.setCustomViewOffset(0);
        dialog[0] = builder.create();
        dialog[0].solegramReadingGlass(); // a warning to read: denser glass, as the plugin report
        fragment.showDialog(dialog[0]);
        // the parts settle in one after another on a soft spring (none of it in power saving)
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                SolegramMotion.staggerIn(root);
                return true;
            }
        });
    }

    /** A "what looks wrong" card: the reasons with a dot of the level's colour. */
    @Nullable
    private static View reasonsCard(Context context, List<SolegramScamRules.Reason> reasons, int accent, int textColor, int grayColor) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        final LinearLayout card = card(context, textColor);
        final TextView header = text(context, getString(R.string.SG_ScamWhy), 13, grayColor);
        header.setTypeface(AndroidUtilities.bold());
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        int shown = 0;
        for (int i = 0; i < reasons.size() && shown < MAX_REASONS; i++) {
            final String reason = SolegramScamGuard.reasonText(reasons.get(i));
            if (TextUtils.isEmpty(reason)) {
                continue;
            }
            card.addView(reasonRow(context, reason, accent, textColor),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, shown == 0 ? 0 : 7, 0, 0));
            shown++;
        }
        return shown > 0 ? card : null;
    }

    private static View reasonRow(Context context, String reason, int accent, int textColor) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final View dot = new View(context);
        final GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        row.addView(dot, LayoutHelper.createLinear(7, 7, Gravity.TOP, 0, 7, 10, 0));
        final TextView text = text(context, reason, 14.5f, textColor);
        text.setLineSpacing(dp(1), 1f);
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * A card on the dialog's glass: a faint tint of the text colour and the hairline rim of Solegram's glass menus,
     * so it reads as a layer of the same glass rather than a grey box.
     */
    private static LinearLayout card(Context context, int textColor) {
        final LinearLayout card = column(context);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        final boolean dark = Theme.isCurrentThemeDark();
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(SolegramGlass.MENU_RADIUS_DP));
        bg.setColor(Theme.multAlpha(textColor, dark ? 0.08f : 0.05f));
        bg.setStroke(Math.max(1, dp(SolegramGlass.MENU_RIM_WIDTH_DP) / 2), dark ? SolegramGlass.MENU_RIM_TOP_DARK : Theme.multAlpha(textColor, 0.07f));
        card.setBackground(bg);
        return card;
    }

    /** The verdict as a small tinted capsule. */
    private static TextView capsule(Context context, String label, int color) {
        final TextView view = text(context, label, 13, color);
        view.setTypeface(AndroidUtilities.bold());
        view.setPadding(dp(12), dp(4), dp(12), dp(5));
        styleCapsule(view, color);
        return view;
    }

    private static void styleCapsule(TextView view, int color) {
        view.setTextColor(color);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Theme.multAlpha(color, 0.14f));
        view.setBackground(bg);
    }

    private static TextView flatButton(Context context, String label, int color) {
        final TextView view = text(context, label, 15, color);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(color, 0.12f), 14, 14));
        return view;
    }

    private static LinearLayout column(Context context) {
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static TextView text(Context context, CharSequence value, float size, int color) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        return view;
    }

    private static void dismiss(AlertDialog[] dialog) {
        if (dialog[0] != null) {
            try {
                dialog[0].dismiss();
            } catch (Throwable ignore) {
            }
        }
    }

    // endregion
}
