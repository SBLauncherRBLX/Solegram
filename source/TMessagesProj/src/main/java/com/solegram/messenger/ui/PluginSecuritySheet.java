/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.plugins.PluginGuard;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginSecurity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/**
 * The security report of a plugin: verdict, permissions with evidence and the file hash. Shown
 * before installing, updating (with the new permissions) and enabling a plugin, and from its page.
 */
public class PluginSecuritySheet extends BottomSheet {

    public static final int MODE_INSTALL = 0;
    public static final int MODE_UPDATE = 1;
    public static final int MODE_ENABLE = 2;
    public static final int MODE_VIEW = 3;

    // Apple HIG system colors (light, dark), as the tiles of Solegram's settings
    private static final int[] GREEN = {0xFF34C759, 0xFF30D158};
    private static final int[] ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] RED = {0xFFFF3B30, 0xFFFF453A};
    private static final int[] BLUE = {0xFF007AFF, 0xFF0A84FF};
    private static final int[] TEAL = {0xFF30B0C7, 0xFF40C8E0};
    private static final int[] YELLOW = {0xFFFFB800, 0xFFFFC70A};
    private static final int[] PURPLE = {0xFFAF52DE, 0xFFBF5AF2};
    private static final int[] INDIGO = {0xFF5856D6, 0xFF5E5CE6};
    private static final int[] PINK = {0xFFFF2D55, 0xFFFF375F};

    private final LinearLayout content;
    private LinearLayout detailsLayout;
    private TextView detailsToggle;

    public PluginSecuritySheet(BaseFragment fragment, PluginMeta meta, PluginSecurity.Report report, PluginMeta installed,
                               boolean blocklisted, int mode, Runnable onConfirm) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Context context = fragment.getParentActivity();
        fixNavigationBar();

        boolean refusedByUser = !blocklisted && mode != MODE_VIEW && SolegramConfig.pluginsBlockDangerous
            && report.verdict == PluginSecurity.VERDICT_DANGER;
        boolean refused = blocklisted && mode != MODE_VIEW || refusedByUser;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // the buttons stay visible however long the report is
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.66f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(20), 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(context, fragment, meta, installed);
        addVerdict(context, report, blocklisted, refusedByUser);
        if (mode == MODE_UPDATE && installed != null && installed.report != null && !blocklisted) {
            addNewPermissions(context, installed, report);
        }
        addPermissions(context, report);
        addDetails(context, report);
        addHash(context, report);

        TextView note = text(context, getString(R.string.SG_SecCheckedOnDevice), 12, getThemedColor(Theme.key_dialogTextGray3));
        content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 12, 22, 0));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider);
        boolean danger = report.verdict == PluginSecurity.VERDICT_DANGER || blocklisted;
        if (refused || mode == MODE_VIEW) {
            button.setText(getString(mode == MODE_VIEW ? R.string.Done : R.string.Close), false);
            button.setOnClickListener(v -> dismiss());
        } else {
            button.setText(getString(mode == MODE_UPDATE ? R.string.SG_PluginUpdate : mode == MODE_ENABLE ? R.string.SG_SecEnable : R.string.SG_PluginInstall), false);
            if (danger) {
                button.setColor(color(RED));
            }
            button.setOnClickListener(v -> {
                if (danger) {
                    confirmDanger(context, meta, report, mode, () -> {
                        dismiss();
                        onConfirm.run();
                    });
                } else {
                    dismiss();
                    onConfirm.run();
                }
            });
        }
        root.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 0));

        if (!refused && mode != MODE_VIEW) {
            TextView cancel = new TextView(context);
            cancel.setText(getString(R.string.Cancel));
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            cancel.setGravity(Gravity.CENTER);
            cancel.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            cancel.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            cancel.setOnClickListener(v -> dismiss());
            root.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 4, 16, 4));
        } else {
            root.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
        }
        setCustomView(root);
        solegramReadingGlass(); // a report to read: a denser glass than other sheets, as the update sheet
    }

    // region Sections

    private void addHeader(Context context, BaseFragment fragment, PluginMeta meta, PluginMeta installed) {
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        BackupImageView icon = new BackupImageView(context);
        icon.setRoundRadius(dp(12));
        icon.setImageResource(R.drawable.msg_bots);
        if (!TextUtils.isEmpty(meta.icon) && meta.icon.contains("/")) {
            try {
                int slash = meta.icon.lastIndexOf('/');
                MediaDataController.getInstance(fragment.getCurrentAccount()).setPlaceholderImageByIndex(icon, meta.icon.substring(0, slash), Integer.parseInt(meta.icon.substring(slash + 1).trim()), "52_52");
            } catch (Exception ignore) {
            }
        }
        head.addView(icon, LayoutHelper.createLinear(52, 52));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(context, meta.name != null ? meta.name : meta.id, 19, getThemedColor(Theme.key_dialogTextBlack));
        name.setTypeface(AndroidUtilities.bold());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(name);
        String version = installed != null && !TextUtils.equals(installed.version, meta.version)
            ? installed.version + " → " + meta.version : meta.version;
        TextView sub = text(context, "v" + version + (TextUtils.isEmpty(meta.author) ? "" : " · " + meta.author), 14, getThemedColor(Theme.key_dialogTextGray3));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        head.addView(titles, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 0, 0));
        content.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));
    }

    /** The colored card: shield, verdict and why. */
    private void addVerdict(Context context, PluginSecurity.Report report, boolean blocklisted, boolean refusedByUser) {
        int color = color(verdictColors(report.verdict, blocklisted));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(Theme.createRoundRectDrawable(dp(14), ColorUtils.setAlphaComponent(color, 0x24)));

        ImageView shield = new ImageView(context);
        shield.setImageResource(verdictIcon(report.verdict, blocklisted));
        shield.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        card.addView(shield, LayoutHelper.createLinear(28, 28, Gravity.TOP, 0, 1, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(context, verdictTitle(report.verdict, blocklisted), 16, color);
        title.setTypeface(AndroidUtilities.bold());
        texts.addView(title);
        TextView summary = text(context, summary(report, blocklisted), 14, getThemedColor(Theme.key_dialogTextBlack));
        summary.setLineSpacing(dp(1.5f), 1f);
        texts.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        if (refusedByUser) {
            TextView blocked = text(context, getString(R.string.SG_SecBlockedByUser), 13, color);
            texts.addView(blocked, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));
    }

    /** An update that asks for more than the installed version: the difference, before anything changes. */
    private void addNewPermissions(Context context, PluginMeta installed, PluginSecurity.Report fresh) {
        List<PluginSecurity.Finding> added = PluginSecurity.newPermissions(installed.report, fresh);
        boolean worse = fresh.verdict > installed.report.verdict;
        if (added.isEmpty() && !worse) {
            return;
        }
        int orange = color(ORANGE);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(Theme.createRoundRectDrawable(dp(14), ColorUtils.setAlphaComponent(orange, 0x1C)));
        TextView title = text(context, getString(R.string.SG_SecNewPermissions), 15, orange);
        title.setTypeface(AndroidUtilities.bold());
        box.addView(title);
        if (worse) {
            box.addView(text(context, LocaleController.formatString(R.string.SG_SecVerdictChanged,
                verdictTitle(installed.report.verdict, false), verdictTitle(fresh.verdict, false)), 13, getThemedColor(Theme.key_dialogTextBlack)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        if (!added.isEmpty()) {
            box.addView(text(context, LocaleController.formatString(R.string.SG_SecNewPermissionsInfo, "v" + installed.version), 13, getThemedColor(Theme.key_dialogTextGray3)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 2));
            box.addView(text(context, ruleList(added), 14, getThemedColor(Theme.key_dialogTextBlack)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        content.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 0));
    }

    private void addPermissions(Context context, PluginSecurity.Report report) {
        content.addView(sectionHeader(context, getString(R.string.SG_SecPermissions)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 4));
        if (report.findings.isEmpty()) {
            content.addView(permissionRow(context, R.drawable.outline_shield_check, GREEN, getString(R.string.SG_SecNoPermissions),
                getString(R.string.SG_SecNoPermissionsInfo), null, 0));
            return;
        }
        for (int category : report.categories()) {
            List<PluginSecurity.Finding> list = report.inCategory(category);
            CharSequence rules = ruleList(list);
            if (category == PluginSecurity.CAT_NETWORK && !report.hosts.isEmpty()) {
                SpannableStringBuilder sb = new SpannableStringBuilder(rules);
                sb.append("\n").append(TextUtils.join(", ", report.hosts.subList(0, Math.min(4, report.hosts.size()))));
                if (report.hosts.size() > 4) {
                    sb.append(" ").append(LocaleController.formatString(R.string.SG_SecMoreLines, report.hosts.size() - 4));
                }
                rules = sb;
            }
            content.addView(permissionRow(context, categoryIcon(category), categoryColors(category), categoryTitle(category),
                categoryInfo(category), rules, report.categorySeverity(category)));
        }
    }

    private View permissionRow(Context context, int icon, int[] colors, String title, String info, CharSequence rules, int severity) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(8));
        row.addView(tileIcon(context, icon, colors, 30), LayoutHelper.createLinear(30, 30, Gravity.TOP, 0, 2, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(context, title, 16, getThemedColor(Theme.key_dialogTextBlack));
        titleView.setTypeface(AndroidUtilities.bold());
        titleRow.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (severity >= PluginSecurity.HIGH) {
            int c = color(severity >= PluginSecurity.CRITICAL ? RED : ORANGE);
            TextView pill = text(context, getString(severity >= PluginSecurity.CRITICAL ? R.string.SG_SecPillCritical : R.string.SG_SecPillHigh), 11, 0xFFFFFFFF);
            pill.setTypeface(AndroidUtilities.bold());
            pill.setPadding(dp(7), dp(1.5f), dp(7), dp(2));
            pill.setBackground(Theme.createRoundRectDrawable(dp(9), c));
            titleRow.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        }
        texts.addView(titleRow);
        texts.addView(text(context, info, 13, getThemedColor(Theme.key_dialogTextGray3)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        if (rules != null) {
            TextView rulesView = text(context, rules, 14, getThemedColor(Theme.key_dialogTextBlack));
            rulesView.setLineSpacing(dp(2), 1f);
            texts.addView(rulesView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        return row;
    }

    /** "Details": every rule with the lines of code that triggered it. */
    private void addDetails(Context context, PluginSecurity.Report report) {
        if (report.findings.isEmpty()) {
            return;
        }
        detailsToggle = text(context, "", 15, getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        detailsToggle.setTypeface(AndroidUtilities.bold());
        detailsToggle.setPadding(dp(22), dp(10), dp(22), dp(10));
        detailsToggle.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        content.addView(detailsToggle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        detailsLayout = new LinearLayout(context);
        detailsLayout.setOrientation(LinearLayout.VERTICAL);
        detailsLayout.setVisibility(View.GONE);
        int codeBg = ColorUtils.setAlphaComponent(getThemedColor(Theme.key_dialogTextBlack), 0x0E);
        for (PluginSecurity.Finding f : report.findings) {
            TextView label = text(context, ruleLabel(f.rule), 13, severityTextColor(f.severity));
            label.setTypeface(AndroidUtilities.bold());
            detailsLayout.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 4));
            if (f.evidence.isEmpty()) {
                continue;
            }
            SpannableStringBuilder code = new SpannableStringBuilder();
            int gray = getThemedColor(Theme.key_dialogTextGray3);
            for (PluginSecurity.Evidence e : f.evidence) {
                if (code.length() > 0) {
                    code.append('\n');
                }
                String number = String.valueOf(e.line);
                int start = code.length();
                code.append(number).append("  ");
                code.setSpan(new ForegroundColorSpan(gray), start, start + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                code.append(e.snippet);
            }
            if (f.count > f.evidence.size()) {
                int start = code.length();
                code.append('\n').append(LocaleController.formatString(R.string.SG_SecMoreLines, f.count - f.evidence.size()));
                code.setSpan(new ForegroundColorSpan(gray), start, code.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView codeView = text(context, code, 12, getThemedColor(Theme.key_dialogTextBlack));
            codeView.setTypeface(Typeface.MONOSPACE);
            codeView.setPadding(dp(10), dp(8), dp(10), dp(8));
            codeView.setBackground(Theme.createRoundRectDrawable(dp(10), codeBg));
            codeView.setTextIsSelectable(true);
            detailsLayout.addView(codeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 0, 18, 2));
        }
        content.addView(detailsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateDetailsToggle(false);
        detailsToggle.setOnClickListener(v -> updateDetailsToggle(true));
    }

    private void updateDetailsToggle(boolean toggle) {
        boolean shown = detailsLayout.getVisibility() == View.VISIBLE;
        if (toggle) {
            android.transition.TransitionManager.beginDelayedTransition(content);
            shown = !shown;
            detailsLayout.setVisibility(shown ? View.VISIBLE : View.GONE);
        }
        detailsToggle.setText((shown ? "▴  " : "▾  ") + getString(shown ? R.string.SG_SecHideDetails : R.string.SG_SecDetails));
    }

    private void addHash(Context context, PluginSecurity.Report report) {
        if (report.sha256 == null) {
            return;
        }
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), dp(8));
        box.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        TextView label = text(context, getString(R.string.SG_SecHash), 12, getThemedColor(Theme.key_dialogTextGray3));
        label.setTypeface(AndroidUtilities.bold());
        box.addView(label);
        TextView hash = text(context, groupedHash(report.sha256), 13, getThemedColor(Theme.key_dialogTextBlack));
        hash.setTypeface(Typeface.MONOSPACE);
        box.addView(hash, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        box.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(report.sha256);
            BulletinFactory.of(getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.SG_SecHashCopied)).show();
        });
        content.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    private void confirmDanger(Context context, PluginMeta meta, PluginSecurity.Report report, int mode, Runnable proceed) {
        String name = meta.name != null ? meta.name : meta.id;
        String reason = lowerFirst(report.reason != null ? ruleLabel(report.reason) : verdictTitle(report.verdict, false));
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(getString(R.string.SG_SecDangerConfirmTitle))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SG_SecDangerConfirm, name, reason)))
            .setPositiveButton(getString(mode == MODE_ENABLE ? R.string.SG_SecEnableAnyway : R.string.SG_SecInstallAnyway), (d, w) -> proceed.run())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        dialog.show();
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }
    // endregion

    // region Views

    private TextView text(Context context, CharSequence value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        return view;
    }

    private TextView sectionHeader(Context context, String value) {
        TextView view = text(context, value, 15, getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    /** A rounded gradient tile with a white glyph, drawn like the icons of Solegram's settings. */
    static ImageView tileIcon(Context context, int icon, int[] colors, int sizeDp) {
        ImageView view = new ImageView(context);
        int base = color(colors);
        SettingsActivity.SettingCell.Background bg = new SettingsActivity.SettingCell.Background();
        bg.setColor(ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
        bg.setDrawBorder(Theme.isCurrentThemeDark());
        view.setBackground(bg);
        Drawable glyph = ContextCompat.getDrawable(context, icon);
        if (glyph != null) {
            LayerDrawable sized = new LayerDrawable(new Drawable[]{glyph.mutate()});
            int glyphSize = dp(sizeDp * 0.66f);
            sized.setLayerSize(0, glyphSize, glyphSize);
            sized.setLayerGravity(0, Gravity.CENTER);
            view.setImageDrawable(sized);
        }
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        return view;
    }

    private CharSequence ruleList(List<PluginSecurity.Finding> list) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (PluginSecurity.Finding f : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int start = sb.length();
            sb.append("•  ").append(ruleLabel(f.rule));
            if (f.severity >= PluginSecurity.HIGH) {
                sb.setSpan(new ForegroundColorSpan(severityTextColor(f.severity)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!f.details.isEmpty() && !f.isCombo()) {
                int s = sb.length();
                sb.append(" · ").append(TextUtils.join(", ", f.details.subList(0, Math.min(3, f.details.size()))));
                sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_dialogTextGray3)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private int severityTextColor(int severity) {
        if (severity >= PluginSecurity.CRITICAL) {
            return color(RED);
        }
        if (severity >= PluginSecurity.HIGH) {
            return color(ORANGE);
        }
        return getThemedColor(Theme.key_dialogTextBlack);
    }

    private static String groupedHash(String sha) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sha.length(); i += 8) {
            if (i > 0) {
                sb.append(i == 32 ? '\n' : ' ');
            }
            sb.append(sha, i, Math.min(sha.length(), i + 8));
        }
        return sb.toString();
    }
    // endregion

    // region Shared helpers (plugin list, plugin page)

    public static int color(int[] colors) {
        return Theme.isCurrentThemeDark() ? colors[1] : colors[0];
    }

    public static int[] verdictColors(int verdict, boolean blocked) {
        if (blocked || verdict == PluginSecurity.VERDICT_DANGER) {
            return RED;
        }
        return verdict == PluginSecurity.VERDICT_WARNING ? ORANGE : GREEN;
    }

    public static int verdictIcon(int verdict, boolean blocked) {
        if (blocked) {
            return R.drawable.msg_block;
        }
        return verdict == PluginSecurity.VERDICT_SAFE ? R.drawable.outline_shield_check : R.drawable.msg_warning;
    }

    public static String verdictTitle(int verdict, boolean blocked) {
        if (blocked) {
            return getString(R.string.SG_SecBlocked);
        }
        switch (verdict) {
            case PluginSecurity.VERDICT_DANGER:
                return getString(R.string.SG_SecDanger);
            case PluginSecurity.VERDICT_WARNING:
                return getString(R.string.SG_SecWarning);
            default:
                return getString(R.string.SG_SecSafe);
        }
    }

    public static String summary(PluginSecurity.Report report, boolean blocked) {
        if (blocked) {
            return getString(R.string.SG_SecSummaryBlocked);
        }
        switch (report.verdict) {
            case PluginSecurity.VERDICT_DANGER:
                return LocaleController.formatString(R.string.SG_SecSummaryDanger, ruleLabel(report.reason));
            case PluginSecurity.VERDICT_WARNING:
                return LocaleController.formatString(R.string.SG_SecSummaryWarning, ruleLabel(report.reason));
            default:
                return getString(report.findings.isEmpty() ? R.string.SG_SecSummarySafeEmpty : R.string.SG_SecSummarySafe);
        }
    }

    /** One line for a category: its rule labels. */
    public static String categoryRules(PluginSecurity.Report report, int category) {
        StringBuilder sb = new StringBuilder();
        for (PluginSecurity.Finding f : report.inCategory(category)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String label = ruleLabel(f.rule);
            sb.append(sb.length() == 0 ? label : lowerFirst(label));
        }
        return sb.toString();
    }

    /** "Reads chats" in the middle of a sentence: only the first letter, names like Telegram stay. */
    private static String lowerFirst(String s) {
        return TextUtils.isEmpty(s) ? s : s.substring(0, 1).toLowerCase(java.util.Locale.getDefault()) + s.substring(1);
    }

    public static UItem tile(UItem item, int[] colors) {
        int base = color(colors);
        return item.setIconTile(ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
    }

    public static int categoryIcon(int category) {
        switch (category) {
            case PluginSecurity.CAT_NETWORK:
                return R.drawable.msg_language;
            case PluginSecurity.CAT_MESSAGES:
                return R.drawable.msg_discussion;
            case PluginSecurity.CAT_ACTIONS:
                return R.drawable.msg_send;
            case PluginSecurity.CAT_SECRETS:
                return R.drawable.msg_secret;
            case PluginSecurity.CAT_FILES:
                return R.drawable.msg2_folder;
            case PluginSecurity.CAT_CODE:
                return R.drawable.menu_feature_code;
            case PluginSecurity.CAT_SYSTEM:
                return R.drawable.msg_settings;
            default:
                return R.drawable.msg_palette;
        }
    }

    public static int[] categoryColors(int category) {
        switch (category) {
            case PluginSecurity.CAT_NETWORK:
                return BLUE;
            case PluginSecurity.CAT_MESSAGES:
                return GREEN;
            case PluginSecurity.CAT_ACTIONS:
                return TEAL;
            case PluginSecurity.CAT_SECRETS:
                return RED;
            case PluginSecurity.CAT_FILES:
                return YELLOW;
            case PluginSecurity.CAT_CODE:
                return PURPLE;
            case PluginSecurity.CAT_SYSTEM:
                return INDIGO;
            default:
                return PINK;
        }
    }

    public static String categoryTitle(int category) {
        switch (category) {
            case PluginSecurity.CAT_NETWORK:
                return getString(R.string.SG_SecCatNetwork);
            case PluginSecurity.CAT_MESSAGES:
                return getString(R.string.SG_SecCatMessages);
            case PluginSecurity.CAT_ACTIONS:
                return getString(R.string.SG_SecCatActions);
            case PluginSecurity.CAT_SECRETS:
                return getString(R.string.SG_SecCatSecrets);
            case PluginSecurity.CAT_FILES:
                return getString(R.string.SG_SecCatFiles);
            case PluginSecurity.CAT_CODE:
                return getString(R.string.SG_SecCatCode);
            case PluginSecurity.CAT_SYSTEM:
                return getString(R.string.SG_SecCatSystem);
            default:
                return getString(R.string.SG_SecCatUi);
        }
    }

    public static String categoryInfo(int category) {
        switch (category) {
            case PluginSecurity.CAT_NETWORK:
                return getString(R.string.SG_SecCatNetworkInfo);
            case PluginSecurity.CAT_MESSAGES:
                return getString(R.string.SG_SecCatMessagesInfo);
            case PluginSecurity.CAT_ACTIONS:
                return getString(R.string.SG_SecCatActionsInfo);
            case PluginSecurity.CAT_SECRETS:
                return getString(R.string.SG_SecCatSecretsInfo);
            case PluginSecurity.CAT_FILES:
                return getString(R.string.SG_SecCatFilesInfo);
            case PluginSecurity.CAT_CODE:
                return getString(R.string.SG_SecCatCodeInfo);
            case PluginSecurity.CAT_SYSTEM:
                return getString(R.string.SG_SecCatSystemInfo);
            default:
                return getString(R.string.SG_SecCatUiInfo);
        }
    }

    /** Text for the install errors of PluginsController (security refusals), or null for other errors. */
    public static String errorText(String error, String name) {
        if (PluginGuard.ERROR_BLOCKLISTED.equals(error)) {
            return LocaleController.formatString(R.string.SG_SecRefusedBlocklist, name);
        }
        if (PluginGuard.ERROR_DANGEROUS.equals(error)) {
            return LocaleController.formatString(R.string.SG_SecRefusedDangerous, name);
        }
        return null;
    }

    /**
     * Turning a plugin on: a plugin whose current file the user has not reviewed shows its report
     * first; blocklisted and (if so set) dangerous plugins are refused. {@code enable} runs on the UI thread.
     */
    public static void confirmEnable(BaseFragment fragment, PluginMeta meta, Runnable enable) {
        if (meta.file == null) {
            enable.run();
            return;
        }
        PluginGuard.scanAsync(meta.file, false, (report, blocklisted, error) -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (report == null) {
                // unreadable file: loading fails on its own with a clearer error
                enable.run();
                return;
            }
            meta.report = report;
            meta.sha256 = report.sha256;
            String name = meta.name != null ? meta.name : meta.id;
            if (SolegramConfig.pluginsScanBeforeInstall && (blocklisted || !PluginGuard.isReviewed(meta.id, report.sha256))) {
                fragment.showDialog(new PluginSecuritySheet(fragment, meta, report, null, blocklisted, MODE_ENABLE, () -> {
                    PluginGuard.markReviewed(meta.id, report.sha256);
                    enable.run();
                }));
            } else if (blocklisted) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_SecRefusedBlocklist, name)).show();
            } else if (SolegramConfig.pluginsBlockDangerous && report.verdict == PluginSecurity.VERDICT_DANGER) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_SecRefusedEnableDangerous, name)).show();
            } else {
                enable.run();
            }
        });
    }

    /** A plugin was stopped by the security checks (blocklist, dangerous plugins blocked). */
    public static void showRefusedNotice(String name, int block) {
        String text = LocaleController.formatString(block == PluginGuard.BLOCK_LIST ? R.string.SG_SecBlocklistDisabled : R.string.SG_SecDangerDisabled, name);
        showGlobal(text, R.raw.error, 0);
    }

    public static void showSafeModeNotice() {
        showGlobal(getString(R.string.SG_SafeModeNotice), R.raw.info, 0);
    }

    /** A bulletin on whatever screen is open; waits for the UI at app start. */
    private static void showGlobal(CharSequence text, int icon, int attempt) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null || fragment.getFragmentView() == null) {
            if (attempt < 10) {
                AndroidUtilities.runOnUIThread(() -> showGlobal(text, icon, attempt + 1), 1500);
            }
            return;
        }
        if (fragment instanceof PluginsActivity) {
            BulletinFactory.of(fragment).createSimpleBulletin(icon, text).show();
        } else {
            BulletinFactory.of(fragment).createSimpleBulletin(icon, text, getString(R.string.Open), () -> fragment.presentFragment(new PluginsActivity())).show();
        }
    }

    public static String ruleLabel(String rule) {
        if (rule == null) {
            return "";
        }
        switch (rule) {
            case "net_python":
                return getString(R.string.SG_SecR_net_python);
            case "net_java":
                return getString(R.string.SG_SecR_net_java);
            case "net_webview":
                return getString(R.string.SG_SecR_net_webview);
            case "net_suspicious":
                return getString(R.string.SG_SecR_net_suspicious);
            case "combo_suspicious_exfil":
                return getString(R.string.SG_SecR_combo_suspicious_exfil);
            case "msg_updates":
                return getString(R.string.SG_SecR_msg_updates);
            case "msg_outgoing":
                return getString(R.string.SG_SecR_msg_outgoing);
            case "msg_requests":
                return getString(R.string.SG_SecR_msg_requests);
            case "msg_read":
                return getString(R.string.SG_SecR_msg_read);
            case "msg_contacts":
                return getString(R.string.SG_SecR_msg_contacts);
            case "msg_database":
                return getString(R.string.SG_SecR_msg_database);
            case "combo_exfil_messages":
                return getString(R.string.SG_SecR_combo_exfil_messages);
            case "act_request":
                return getString(R.string.SG_SecR_act_request);
            case "act_send":
                return getString(R.string.SG_SecR_act_send);
            case "act_edit":
                return getString(R.string.SG_SecR_act_edit);
            case "act_forward":
                return getString(R.string.SG_SecR_act_forward);
            case "act_delete":
                return getString(R.string.SG_SecR_act_delete);
            case "act_join":
                return getString(R.string.SG_SecR_act_join);
            case "act_profile":
                return getString(R.string.SG_SecR_act_profile);
            case "act_payments":
                return getString(R.string.SG_SecR_act_payments);
            case "sec_session_files":
                return getString(R.string.SG_SecR_sec_session_files);
            case "sec_auth_internals":
                return getString(R.string.SG_SecR_sec_auth_internals);
            case "sec_prefs":
                return getString(R.string.SG_SecR_sec_prefs);
            case "sec_login_accept":
                return getString(R.string.SG_SecR_sec_login_accept);
            case "sec_login_export":
                return getString(R.string.SG_SecR_sec_login_export);
            case "sec_2fa":
                return getString(R.string.SG_SecR_sec_2fa);
            case "sec_sessions":
                return getString(R.string.SG_SecR_sec_sessions);
            case "sec_account":
                return getString(R.string.SG_SecR_sec_account);
            case "sec_login_codes":
                return getString(R.string.SG_SecR_sec_login_codes);
            case "sec_phone":
                return getString(R.string.SG_SecR_sec_phone);
            case "sec_clipboard":
                return getString(R.string.SG_SecR_sec_clipboard);
            case "sec_keystore":
                return getString(R.string.SG_SecR_sec_keystore);
            case "sec_plugins_control":
                return getString(R.string.SG_SecR_sec_plugins_control);
            case "sec_tamper":
                return getString(R.string.SG_SecR_sec_tamper);
            case "combo_exfil_secrets":
                return getString(R.string.SG_SecR_combo_exfil_secrets);
            case "file_io":
                return getString(R.string.SG_SecR_file_io);
            case "file_listing":
                return getString(R.string.SG_SecR_file_listing);
            case "file_delete":
                return getString(R.string.SG_SecR_file_delete);
            case "file_private_dir":
                return getString(R.string.SG_SecR_file_private_dir);
            case "file_shared_storage":
                return getString(R.string.SG_SecR_file_shared_storage);
            case "file_archive":
                return getString(R.string.SG_SecR_file_archive);
            case "combo_private_network":
                return getString(R.string.SG_SecR_combo_private_network);
            case "code_exec":
                return getString(R.string.SG_SecR_code_exec);
            case "code_exec_static":
                return getString(R.string.SG_SecR_code_exec_static);
            case "code_import":
                return getString(R.string.SG_SecR_code_import);
            case "code_dynamic_import":
                return getString(R.string.SG_SecR_code_dynamic_import);
            case "code_deserialize":
                return getString(R.string.SG_SecR_code_deserialize);
            case "code_decode":
                return getString(R.string.SG_SecR_code_decode);
            case "code_blob":
                return getString(R.string.SG_SecR_code_blob);
            case "code_hidden_string":
                return getString(R.string.SG_SecR_code_hidden_string);
            case "code_escapes":
                return getString(R.string.SG_SecR_code_escapes);
            case "code_chr":
                return getString(R.string.SG_SecR_code_chr);
            case "code_getattr":
                return getString(R.string.SG_SecR_code_getattr);
            case "code_native":
                return getString(R.string.SG_SecR_code_native);
            case "code_packages":
                return getString(R.string.SG_SecR_code_packages);
            case "combo_hidden_code":
                return getString(R.string.SG_SecR_combo_hidden_code);
            case "combo_remote_code":
                return getString(R.string.SG_SecR_combo_remote_code);
            case "combo_obfuscated_network":
                return getString(R.string.SG_SecR_combo_obfuscated_network);
            case "sys_shell":
                return getString(R.string.SG_SecR_sys_shell);
            case "sys_reflection":
                return getString(R.string.SG_SecR_sys_reflection);
            case "sys_hooks":
                return getString(R.string.SG_SecR_sys_hooks);
            case "sys_dex":
                return getString(R.string.SG_SecR_sys_dex);
            case "sys_native_lib":
                return getString(R.string.SG_SecR_sys_native_lib);
            case "sys_install":
                return getString(R.string.SG_SecR_sys_install);
            case "sys_permissions":
                return getString(R.string.SG_SecR_sys_permissions);
            case "sys_apps":
                return getString(R.string.SG_SecR_sys_apps);
            case "sys_intents":
                return getString(R.string.SG_SecR_sys_intents);
            case "combo_shell_network":
                return getString(R.string.SG_SecR_combo_shell_network);
            case "ui_overlay":
                return getString(R.string.SG_SecR_ui_overlay);
            case "ui_settings":
                return getString(R.string.SG_SecR_ui_settings);
            case "ui_notifications":
                return getString(R.string.SG_SecR_ui_notifications);
            case "ui_menu":
                return getString(R.string.SG_SecR_ui_menu);
            case "ui_links":
                return getString(R.string.SG_SecR_ui_links);
            default:
                return rule;
        }
    }
    // endregion
}
