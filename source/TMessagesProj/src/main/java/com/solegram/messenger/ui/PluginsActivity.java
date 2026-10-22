/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.content.Context;
import android.view.View;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.telegram.messenger.LocaleController.getString;

/** Installed plugins, engine switches and installation from a file. */
public class PluginsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENGINE = 1;
    public static final int ID_SAFE_MODE = 2;
    public static final int ID_DEV_MODE = 3;
    private static final int ID_INSTALL = 4;
    private static final int ID_CATALOG = 5;
    public static final int ID_SCAN = 6;
    public static final int ID_BLOCK_DANGEROUS = 7;
    private static final int REQUEST_PICK_PLUGIN = 0x5052;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramPluginsChanged);
        if (PluginsController.getInstance().wasSafeModeEnabledAutomatically()) {
            AndroidUtilities.runOnUIThread(this::showAutoSafeModeDialog, 300);
        }
        // a fresh copy of the blocklist when it is older than a few hours (background, no-op otherwise)
        com.solegram.messenger.plugins.PluginGuard.refreshBlocklist(false, null);
        return super.onFragmentCreate();
    }

    /** Safe mode was turned on after a crash: say which plugin was loading and offer to turn it off. */
    private void showAutoSafeModeDialog() {
        PluginsController controller = PluginsController.getInstance();
        if (getParentActivity() == null) {
            return;
        }
        String id = controller.getSafeModePlugin();
        PluginMeta meta = id != null ? controller.getPlugin(id) : null;
        String name = meta != null && meta.name != null ? meta.name : id;
        int reason = controller.getSafeModeReason();
        String message;
        if (name != null && reason == PluginsController.SAFE_MODE_CRASH_WHILE_LOADING) {
            message = LocaleController.formatString(R.string.SG_SafeModeLoading, name);
        } else if (name != null && reason == PluginsController.SAFE_MODE_CRASH_LOOP) {
            message = LocaleController.formatString(R.string.SG_SafeModeLoop, name);
        } else {
            message = getString(R.string.SG_SafeModeAuto);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_SafeMode))
            .setMessage(message);
        if (meta != null) {
            builder.setPositiveButton(getString(R.string.SG_SafeModeDisablePlugin), (d, w) -> {
                controller.setEnabled(meta.id, false);
                SolegramConfig.putBoolean("pluginsSafeMode", false);
                controller.applyEngineState();
                listView.adapter.update(true);
            });
            builder.setNegativeButton(getString(R.string.OK), null);
        } else {
            builder.setPositiveButton(getString(R.string.OK), null);
        }
        showDialog(builder.create());
        controller.clearAutoSafeModeFlag();
    }

    /** Shows one row of the page (opened from the settings search). */
    public void highlightItem(int itemId) {
        if (listView == null) {
            return;
        }
        int position = listView.findPositionByItemId(itemId);
        if (position < 0) {
            return;
        }
        listView.highlightRow(() -> {
            listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
            return position;
        });
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramPluginsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.solegramPluginsChanged && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        // rounded section cards, as in Telegram's own settings
        listView.setSections();
        return fragmentView;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_Plugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginsController controller = PluginsController.getInstance();
        boolean available = PluginsController.isEngineAvailable();
        items.add(UItem.asRippleCheck(ID_ENGINE, getString(R.string.SG_PluginsEngine)).setChecked(SolegramConfig.pluginsEngine && available).setEnabled(available));
        String engineInfo;
        if (!available) {
            engineInfo = getString(R.string.SG_PluginsEngineMissing);
        } else if (controller.getEngineError() != null) {
            engineInfo = LocaleController.formatString(R.string.SG_PluginsEngineFailed, controller.getEngineError());
        } else {
            engineInfo = LocaleController.formatString(R.string.SG_PluginsEngineInfo, PluginsController.getEngine().describe());
        }
        items.add(UItem.asShadow(engineInfo));

        List<PluginMeta> plugins = controller.getPlugins();
        if (!plugins.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.formatString(R.string.SG_InstalledPlugins, plugins.size())));
            for (PluginMeta meta : plugins) {
                items.add(PluginCell.Factory.of(meta));
            }
            items.add(UItem.asShadow(getString(R.string.SG_PluginsListInfo)));
        }

        items.add(UItem.asButton(ID_INSTALL, R.drawable.msg_add, getString(R.string.SG_InstallPlugin)).accent());
        items.add(UItem.asButton(ID_CATALOG, R.drawable.msg_channel, getString(R.string.SG_PluginsCatalog), "@exteraPlugins"));
        items.add(UItem.asShadow(getString(R.string.SG_PluginsCompat)));

        items.add(UItem.asHeader(getString(R.string.SG_SecSection)));
        items.add(UItem.asButtonCheck(ID_SCAN, getString(R.string.SG_SecScanBeforeInstall), getString(R.string.SG_SecScanBeforeInstallInfo)).setChecked(SolegramConfig.pluginsScanBeforeInstall));
        items.add(UItem.asButtonCheck(ID_BLOCK_DANGEROUS, getString(R.string.SG_SecBlockDangerous), getString(R.string.SG_SecBlockDangerousInfo)).setChecked(SolegramConfig.pluginsBlockDangerous));
        items.add(UItem.asShadow(getString(R.string.SG_SecSettingsInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Advanced)));
        items.add(UItem.asButtonCheck(ID_SAFE_MODE, getString(R.string.SG_SafeMode), getString(R.string.SG_SafeModeInfo)).setChecked(SolegramConfig.pluginsSafeMode));
        items.add(UItem.asButtonCheck(ID_DEV_MODE, getString(R.string.SG_DevMode), getString(R.string.SG_DevModeInfo)).setChecked(SolegramConfig.pluginsDevMode));
        items.add(UItem.asShadow(getString(R.string.SG_PluginsWarning)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof PluginMeta) {
            PluginMeta meta = (PluginMeta) item.object;
            boolean onSwitch = x > view.getWidth() - AndroidUtilities.dp(80);
            if (onSwitch || !meta.loaded) {
                boolean enable = !meta.enabled;
                if (enable && !PluginsController.getInstance().isEngineRunning()) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_PluginsEngineOff)).show();
                    return;
                }
                if (enable) {
                    // Solegram: verified (and, if never reviewed, shown) before its code runs
                    PluginSecuritySheet.confirmEnable(this, meta, () -> {
                        PluginsController.getInstance().setEnabled(meta.id, true);
                        if (listView != null) {
                            listView.adapter.update(true);
                        }
                    });
                    return;
                }
                PluginsController.getInstance().setEnabled(meta.id, false);
                if (view instanceof PluginCell) {
                    ((PluginCell) view).setChecked(false);
                }
            } else {
                openPlugin(meta);
            }
            return;
        }
        switch (item.id) {
            case ID_ENGINE: {
                if (!PluginsController.isEngineAvailable()) {
                    return;
                }
                boolean value = SolegramConfig.toggle("pluginsEngine");
                if (view instanceof TextCheckCell) {
                    TextCheckCell cell = (TextCheckCell) view;
                    cell.setChecked(value);
                    cell.setBackgroundColorAnimated(value, Theme.getColor(value ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                }
                PluginsController.getInstance().applyEngineState();
                break;
            }
            case ID_SAFE_MODE: {
                boolean value = SolegramConfig.toggle("pluginsSafeMode");
                ((NotificationsCheckCell) view).setChecked(value);
                PluginsController.getInstance().applyEngineState();
                break;
            }
            case ID_DEV_MODE: {
                boolean value = SolegramConfig.toggle("pluginsDevMode");
                ((NotificationsCheckCell) view).setChecked(value);
                PluginsController.getInstance().applyDevMode();
                break;
            }
            case ID_SCAN: {
                boolean value = SolegramConfig.toggle("pluginsScanBeforeInstall");
                ((NotificationsCheckCell) view).setChecked(value);
                break;
            }
            case ID_BLOCK_DANGEROUS: {
                boolean value = SolegramConfig.toggle("pluginsBlockDangerous");
                ((NotificationsCheckCell) view).setChecked(value);
                PluginsController.getInstance().applySecurityPolicy();
                break;
            }
            case ID_INSTALL:
                pickPluginFile();
                break;
            case ID_CATALOG:
                Browser.openUrl(getParentActivity(), "https://t.me/exteraPlugins");
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof PluginMeta)) {
            return false;
        }
        PluginMeta meta = (PluginMeta) item.object;
        ItemOptions.makeOptions(this, view)
            .addIf(meta.loaded, R.drawable.msg_settings, getString(R.string.SG_PluginSettings), () -> openPlugin(meta))
            .addIf(meta.report != null, R.drawable.outline_shield_check, getString(R.string.SG_SecSection), () -> openPlugin(meta))
            .add(R.drawable.msg_retry, getString(R.string.SG_PluginReload), () -> {
                PluginsController.getInstance().reloadPlugin(meta.id);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_PluginReloaded, meta.name)).show();
            })
            .add(R.drawable.msg_shareout, getString(R.string.SG_PluginShare), () -> sharePlugin(meta))
            .add(R.drawable.msg_delete, getString(R.string.SG_PluginDelete), true, () -> confirmDelete(meta))
            .show();
        return true;
    }

    private void openPlugin(PluginMeta meta) {
        presentFragment(new PluginSettingsActivity(meta.id));
    }

    private void confirmDelete(PluginMeta meta) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_PluginDelete))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.SG_PluginDeleteConfirm, meta.name)))
            .setPositiveButton(getString(R.string.Delete), (d, w) -> PluginsController.getInstance().delete(meta.id, () -> listView.adapter.update(true)))
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void sharePlugin(PluginMeta meta) {
        Activity activity = getParentActivity();
        if (activity == null || meta.file == null) {
            return;
        }
        try {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "solegram");
            dir.mkdirs();
            File out = new File(dir, meta.id + ".plugin");
            try (InputStream in = new java.io.FileInputStream(meta.file); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                }
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", out);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, meta.name));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void pickPluginFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_PLUGIN);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_PLUGIN || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "solegram_plugins_import");
            dir.mkdirs();
            File out = new File(dir, "import_" + System.currentTimeMillis() + ".plugin");
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(data.getData()); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r;
                while (in != null && (r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                }
            }
            PluginInstallSheet.show(this, out);
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(TextUtils.isEmpty(e.getMessage()) ? getString(R.string.SG_PluginInstallFailed) : e.getMessage()).show();
        }
    }
}
