/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.plugins.PluginGuard;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

import static org.telegram.messenger.LocaleController.getString;

/** Confirmation shown before installing a .plugin file (from a chat or from the file picker). */
public final class PluginInstallSheet {

    private PluginInstallSheet() {
    }

    public static void show(BaseFragment fragment, File file) {
        if (fragment == null || fragment.getParentActivity() == null || file == null) {
            return;
        }
        if (!PluginsController.isEngineAvailable()) {
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.SG_PluginsEngineMissing)).show();
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        PluginsController.getInstance().inspect(file, (meta, error) -> {
            if (meta == null || error != null) {
                progress.dismiss();
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_PluginInvalid, error != null ? error : "?")).show();
                return;
            }
            // Solegram: the file is checked before anything is shown or installed
            PluginGuard.scanAsync(file, false, (report, blocklisted, scanError) -> {
                progress.dismiss();
                if (fragment.getParentActivity() == null) {
                    return;
                }
                String name = meta.name != null ? meta.name : meta.id;
                if (report == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_SecScanFailed, scanError != null ? scanError : "?")).show();
                    return;
                }
                PluginMeta installed = PluginsController.getInstance().getPlugin(meta.id);
                if (SolegramConfig.pluginsScanBeforeInstall) {
                    fragment.showDialog(new PluginSecuritySheet(fragment, meta, report, installed, blocklisted,
                        installed != null ? PluginSecuritySheet.MODE_UPDATE : PluginSecuritySheet.MODE_INSTALL, () -> install(fragment, file, true, name)));
                } else if (blocklisted) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_SecRefusedBlocklist, name)).show();
                } else {
                    showConfirm(fragment, file, meta);
                }
            });
        });
    }

    private static void showConfirm(BaseFragment fragment, File file, PluginMeta meta) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        PluginMeta installed = PluginsController.getInstance().getPlugin(meta.id);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        BackupImageView icon = new BackupImageView(context);
        icon.setImageResource(R.drawable.msg_bots);
        if (!TextUtils.isEmpty(meta.icon) && meta.icon.contains("/")) {
            try {
                int slash = meta.icon.lastIndexOf('/');
                MediaDataController.getInstance(fragment.getCurrentAccount()).setPlaceholderImageByIndex(icon, meta.icon.substring(0, slash), Integer.parseInt(meta.icon.substring(slash + 1).trim()), "56_56");
            } catch (Exception ignore) {
            }
        }
        head.addView(icon, LayoutHelper.createLinear(56, 56));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(meta.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titles.addView(name);
        TextView sub = new TextView(context);
        String version = installed != null && !TextUtils.equals(installed.version, meta.version)
            ? installed.version + " → " + meta.version : meta.version;
        sub.setText("v" + version + (TextUtils.isEmpty(meta.author) ? "" : " · " + meta.author));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        titles.addView(sub);
        head.addView(titles, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 0, 0));
        layout.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(meta.description)) {
            TextView desc = new TextView(context);
            desc.setText(PluginCell.stripMarkdown(meta.description));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            desc.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            layout.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        }

        SpannableStringBuilder details = new SpannableStringBuilder();
        details.append(LocaleController.formatString(R.string.SG_PluginIdLine, meta.id));
        if (meta.requirements != null && !meta.requirements.isEmpty()) {
            details.append("\n").append(LocaleController.formatString(R.string.SG_PluginRequirements, TextUtils.join(", ", meta.requirements)));
        }
        if (!TextUtils.isEmpty(meta.appVersion)) {
            details.append("\n").append(LocaleController.formatString(R.string.SG_PluginAppVersion, meta.appVersion));
        }
        TextView detailsView = new TextView(context);
        detailsView.setText(details);
        detailsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        detailsView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        layout.addView(detailsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        TextView warning = new TextView(context);
        warning.setText(getString(R.string.SG_PluginTrustWarning));
        warning.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        warning.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        layout.addView(warning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 4));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(installed != null ? getString(R.string.SG_PluginUpdateTitle) : getString(R.string.SG_PluginInstallTitle));
        builder.setView(layout);
        builder.setPositiveButton(installed != null ? getString(R.string.SG_PluginUpdate) : getString(R.string.SG_PluginInstall), (d, w) -> install(fragment, file, false, meta.name != null ? meta.name : meta.id));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create().solegramReadingGlass()); // the denser glass of the security report
    }

    private static void install(BaseFragment fragment, File file, boolean reviewed, String name) {
        AlertDialog progress = fragment.getParentActivity() != null ? new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER) : null;
        if (progress != null) {
            progress.showDelayed(200);
        }
        PluginsController.getInstance().install(file, reviewed, (meta, error) -> {
            if (progress != null) {
                progress.dismiss();
            }
            String refused = meta == null ? PluginSecuritySheet.errorText(error, name) : null;
            if (refused != null) {
                BulletinFactory.of(fragment).createErrorBulletin(refused).show();
            } else if (meta == null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_PluginInvalid, error != null ? error : "?")).show();
            } else if (error != null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.SG_PluginError, error)).show();
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_PluginInstalled, meta.name), getString(R.string.SG_PluginSettings), () -> fragment.presentFragment(new PluginSettingsActivity(meta.id))).show();
            }
        });
    }
}
