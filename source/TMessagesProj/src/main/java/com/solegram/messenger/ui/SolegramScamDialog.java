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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramScamGuard;
import com.solegram.messenger.SolegramScamRules;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * The warning before a suspicious link opens: the real site in big letters with the deceiving part in colour,
 * why it looks wrong, and "Don't open" as the main answer. "Check with AI" asks for a second opinion right here.
 */
public final class SolegramScamDialog {

    private static final int[] ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] RED = {0xFFFF3B30, 0xFFFF453A};
    private static final int MAX_REASONS = 5;

    private SolegramScamDialog() {
    }

    /** @param open opens the link (after "Open anyway") */
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
        final AlertDialog[] dialog = new AlertDialog[1];

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(10));

        final View icon = PluginSecuritySheet.tileIcon(context, R.drawable.msg_warning, level == SolegramScamRules.DANGER ? RED : ORANGE, 52);
        root.addView(icon, LayoutHelper.createLinear(52, 52, Gravity.CENTER_HORIZONTAL));

        final TextView title = text(context, getString(R.string.SG_ScamLinkTitle), 19, textColor);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        final TextView verdict = text(context, getString(level == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamLevelCaution), 14, accent);
        verdict.setGravity(Gravity.CENTER);
        root.addView(verdict, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        // the site, as big as a name: what the user is about to trust
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        final GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(18));
        cardBg.setColor(Theme.multAlpha(textColor, 0.06f));
        card.setBackground(cardBg);

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
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        if (report.site != null && report.displayHost != null && report.telegramUsername == null && !report.site.equals(report.host)
            && !report.site.equals(report.displayHost)) {
            final TextView owner = text(context, AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SG_ScamRealSite, report.site)), 13, grayColor);
            owner.setGravity(Gravity.CENTER);
            root.addView(owner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        // why
        final List<SolegramScamRules.Reason> reasons = link.reasons();
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < reasons.size() && i < MAX_REASONS; i++) {
            list.addView(reasonRow(context, SolegramScamGuard.reasonText(reasons.get(i)), accent, textColor),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, i == 0 ? 0 : 8, 0, 0));
        }
        if (list.getChildCount() > 0) {
            root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 16, 2, 0));
        }

        // what AI said (Gemini Nano may have looked already)
        final TextView ai = text(context, "", 14, textColor);
        ai.setPadding(dp(14), dp(10), dp(14), dp(10));
        final GradientDrawable aiBg = new GradientDrawable();
        aiBg.setCornerRadius(dp(16));
        aiBg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, rp), 0.1f));
        ai.setBackground(aiBg);
        ai.setVisibility(View.GONE);
        root.addView(ai, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        if (link.aiLevel >= 0) {
            ai.setText(SolegramScamGuard.verdictLine(link));
            ai.setVisibility(View.VISIBLE);
        }

        // "trust this site": only for web sites (a Telegram username is not a site)
        final boolean canTrust = report.site != null && report.telegramUsername == null && !report.telegramService && report.host != null;
        final CheckBoxCell trust = canTrust ? new CheckBoxCell(context, 1, rp) : null;
        if (trust != null) {
            trust.setText(getString(R.string.SG_ScamTrustSite), "", false, false);
            trust.setPadding(LocaleController.isRTL ? dp(8) : 0, 0, LocaleController.isRTL ? 0 : dp(8), 0);
            trust.setBackground(Theme.getSelectorDrawable(false));
            trust.setOnClickListener(v -> trust.setChecked(!trust.isChecked(), true));
            root.addView(trust, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -14, 8, -14, 0));
        }

        // the safe answer is the big one
        final ButtonWithCounterView dontOpen = new ButtonWithCounterView(context, rp);
        dontOpen.setText(getString(R.string.SG_ScamDontOpen), false);
        dontOpen.setOnClickListener(v -> dismiss(dialog));
        root.addView(dontOpen, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, trust != null ? 10 : 18, 0, 0));

        final TextView askAi = flatButton(context, getString(R.string.SG_ScamCheckAi), Theme.getColor(Theme.key_featuredStickers_addButton, rp), rp);
        askAi.setOnClickListener(v -> {
            askAi.setEnabled(false);
            askAi.setText(getString(R.string.SG_ScamAiChecking));
            SolegramScamGuard.askAi(link, (answer, error) -> {
                askAi.setEnabled(true);
                askAi.setText(getString(R.string.SG_ScamCheckAi));
                if (answer == null && error == null) {
                    return; // declined the online service
                }
                ai.setText(answer != null ? answer : error);
                ai.setVisibility(View.VISIBLE);
                if (answer != null) {
                    askAi.setVisibility(View.GONE);
                    final int now = Math.max(SolegramScamRules.CAUTION, link.level());
                    verdict.setTextColor(SolegramScamGuard.levelColor(now));
                    verdict.setText(getString(link.level() == SolegramScamRules.SAFE ? R.string.SG_ScamAiSafe
                        : now == SolegramScamRules.DANGER ? R.string.SG_ScamLevelDanger : R.string.SG_ScamLevelCaution));
                }
            });
        });
        if (link.aiLevel >= 0) {
            askAi.setVisibility(View.GONE);
        }
        root.addView(askAi, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 6, 0, 0));

        final TextView openAnyway = flatButton(context, getString(R.string.SG_ScamOpenAnyway), Theme.getColor(Theme.key_text_RedBold, rp), rp);
        openAnyway.setOnClickListener(v -> {
            if (trust != null && trust.isChecked()) {
                SolegramScamGuard.trustSite(report.site);
            }
            dismiss(dialog);
            open.run();
        });
        root.addView(openAnyway, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context, rp);
        builder.setView(root);
        builder.setCustomViewOffset(0);
        dialog[0] = builder.create();
        dialog[0].solegramReadingGlass(); // a warning to read: denser glass, as the plugin report
        fragment.showDialog(dialog[0]);
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

    private static View reasonRow(Context context, String reason, int accent, int textColor) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final View dot = new View(context);
        final GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        row.addView(dot, LayoutHelper.createLinear(6, 6, Gravity.TOP, 0, 8, 10, 0));
        final TextView text = text(context, reason, 14.5f, textColor);
        text.setLineSpacing(dp(1), 1f);
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private static TextView flatButton(Context context, String label, int color, Theme.ResourcesProvider rp) {
        final TextView view = text(context, label, 15, color);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(color, 0.12f), 12, 12));
        return view;
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
}
