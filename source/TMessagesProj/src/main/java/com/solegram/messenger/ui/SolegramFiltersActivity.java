/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramFilterEngine;
import com.solegram.messenger.SolegramMessageFilters;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * "Message filters" in Solegram's settings: the main switch, how hidden messages look, the presets,
 * the user's rules with their statistics, chats without filters and moving rules between devices.
 */
public class SolegramFiltersActivity extends UniversalFragment {

    public static final int ID_MASTER = 1;
    public static final int ID_MODE_COLLAPSE = 2;
    public static final int ID_MODE_HIDE = 3;
    public static final int ID_PRESET_ADS = 4;
    public static final int ID_PRESET_JUNK = 5;
    public static final int ID_PRESET_INVITES = 6;
    public static final int ID_ADD = 7;
    public static final int ID_STATS_TODAY = 8;
    public static final int ID_STATS_RESET = 9;
    public static final int ID_EXCLUDED = 10;
    public static final int ID_EXPORT = 11;
    public static final int ID_IMPORT = 12;
    public static final int ID_ADS_STRICT = 13;
    public static final int ID_HIDE_SPONSORED = 14;
    private static final int ID_RULE_BASE = 1000;

    private static final int REQUEST_IMPORT = 0x5346;

    // the same tile colours as the rest of Solegram's settings (Apple HIG, light / dark)
    private static final int[] TILE_RED = {0xFFFF3B30, 0xFFFF453A};
    private static final int[] TILE_ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] TILE_PURPLE = {0xFFAF52DE, 0xFFBF5AF2};
    private static final int[] TILE_BLUE = {0xFF007AFF, 0xFF0A84FF};
    private static final int[] TILE_TEAL = {0xFF30B0C7, 0xFF40C8E0};
    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};
    private static final int[] TILE_GRAY = {0xFF8E8E93, 0xFF8E8E93};
    private static final int[] TILE_CYAN = {0xFF32ADE6, 0xFF64D2FF};

    private int pendingHighlight;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_Filters);
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        if (pendingHighlight != 0) {
            final int id = pendingHighlight;
            pendingHighlight = 0;
            AndroidUtilities.runOnUIThread(() -> highlightItem(id), 300);
        }
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asRippleCheck(ID_MASTER, getString(R.string.SG_Filters)).setChecked(SolegramConfig.messageFilters));
        items.add(UItem.asShadow(getString(R.string.SG_FiltersInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_FiltersLook)));
        final boolean hide = SolegramMessageFilters.mode() == SolegramMessageFilters.MODE_HIDE;
        items.add(UItem.asRadio(ID_MODE_COLLAPSE, getString(R.string.SG_FiltersCollapse)).setChecked(!hide));
        items.add(UItem.asRadio(ID_MODE_HIDE, getString(R.string.SG_FiltersHide)).setChecked(hide));
        items.add(UItem.asShadow(getString(hide ? R.string.SG_FiltersHideInfo : R.string.SG_FiltersCollapseInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_FiltersPresets)));
        items.add(UItem.asButtonCheck(ID_PRESET_ADS, getString(R.string.SG_FilterPresetAds), presetSubtitle(SolegramFilterEngine.PRESET_ADS, R.string.SG_FilterPresetAdsInfo))
            .setChecked(SolegramConfig.filterAds));
        if (SolegramConfig.filterAds) {
            items.add(UItem.asButtonCheck(ID_ADS_STRICT, getString(R.string.SG_FilterAdsStrict), getString(R.string.SG_FilterAdsStrictInfo))
                .setChecked(SolegramConfig.filterAdsStrict));
        }
        items.add(UItem.asButtonCheck(ID_PRESET_JUNK, getString(R.string.SG_FilterPresetJunk), presetSubtitle(SolegramFilterEngine.PRESET_JUNK, R.string.SG_FilterPresetJunkInfo))
            .setChecked(SolegramConfig.filterJunk));
        items.add(UItem.asButtonCheck(ID_PRESET_INVITES, getString(R.string.SG_FilterPresetInvites), presetSubtitle(SolegramFilterEngine.PRESET_INVITES, R.string.SG_FilterPresetInvitesInfo))
            .setChecked(SolegramConfig.filterInvites));
        items.add(UItem.asShadow(getString(R.string.SG_FiltersPresetsInfo)));

        // not a filter: Telegram's own ads are simply not requested, whether the filters are on or not
        items.add(UItem.asHeader(getString(R.string.SG_SponsoredHeader)));
        items.add(UItem.asCheck(ID_HIDE_SPONSORED, getString(R.string.SG_HideSponsored)).setChecked(SolegramConfig.hideSponsored));
        items.add(UItem.asShadow(getString(R.string.SG_HideSponsoredInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_FiltersMine)));
        final ArrayList<SolegramFilterEngine.Rule> rules = SolegramMessageFilters.getRules();
        for (int i = 0; i < rules.size(); i++) {
            final SolegramFilterEngine.Rule r = rules.get(i);
            final CharSequence value = !r.enabled ? getString(R.string.SG_Off)
                : r.getError() != null || r.isSuspended() ? getString(R.string.SG_FilterBroken) : hitsValue(r.id);
            final UItem item = tile(UItem.asButton(ID_RULE_BASE + i, typeIcon(r.type), SolegramMessageFilters.titleOf(r), value), typeColor(r.type, r.enabled))
                .setSolegramSubtitle(SolegramMessageFilters.typeName(r.type) + " · " + SolegramMessageFilters.scopeName(r));
            items.add(item);
        }
        items.add(UItem.asButton(ID_ADD, R.drawable.msg_add, getString(R.string.SG_FilterAdd)).accent());
        items.add(UItem.asShadow(getString(rules.isEmpty() ? R.string.SG_FiltersMineEmpty : R.string.SG_FiltersMineInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_FiltersStats)));
        items.add(tile(UItem.asButton(ID_STATS_TODAY, R.drawable.msg_stats, getString(R.string.SG_FiltersHiddenToday),
            LocaleController.formatNumber(SolegramMessageFilters.hitsTodayAll(), ' ')), TILE_BLUE)
            .setSolegramSubtitle(LocaleController.formatString(R.string.SG_FiltersHiddenTotal, LocaleController.formatNumber(SolegramMessageFilters.hitsTotalAll(), ' '))));
        items.add(UItem.asButton(ID_STATS_RESET, R.drawable.msg_reset, getString(R.string.SG_FiltersStatsReset)).red());
        items.add(UItem.asShadow(getString(R.string.SG_FiltersStatsInfo)));

        final int excludedCount = SolegramMessageFilters.getExcludedChats().size();
        items.add(tile(UItem.asButton(ID_EXCLUDED, R.drawable.msg_chats_remove, getString(R.string.SG_FiltersExcluded),
            excludedCount == 0 ? getString(R.string.SG_FiltersExcludedNone) : String.valueOf(excludedCount)), TILE_GRAY));
        items.add(UItem.asShadow(getString(R.string.SG_FiltersExcludedInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_FiltersTransfer)));
        items.add(tile(UItem.asButton(ID_EXPORT, R.drawable.msg_shareout, getString(R.string.SG_FiltersExport)), TILE_TEAL));
        items.add(tile(UItem.asButton(ID_IMPORT, R.drawable.msg_download, getString(R.string.SG_FiltersImport)), TILE_CYAN));
        items.add(UItem.asShadow(getString(R.string.SG_FiltersTransferInfo)));
    }

    private CharSequence presetSubtitle(long id, int info) {
        final long total = SolegramMessageFilters.hitsTotal(id);
        if (total == 0) {
            return getString(info);
        }
        return getString(info) + "\n" + hitsLine(id);
    }

    private static String hitsValue(long id) {
        final long total = SolegramMessageFilters.hitsTotal(id);
        return total == 0 ? "" : LocaleController.formatNumber(total, ' ');
    }

    /** "Hidden: 3 today, 120 in all" */
    static String hitsLine(long id) {
        return LocaleController.formatString(R.string.SG_FilterHits,
            LocaleController.formatNumber(SolegramMessageFilters.hitsToday(id), ' '),
            LocaleController.formatNumber(SolegramMessageFilters.hitsTotal(id), ' '));
    }

    static int typeIcon(int type) {
        switch (type) {
            case SolegramFilterEngine.TYPE_REGEX:
                return R.drawable.msg_text_outlined;
            case SolegramFilterEngine.TYPE_SENDER:
                return R.drawable.msg_user_remove;
            case SolegramFilterEngine.TYPE_FORWARD:
                return R.drawable.msg_forward;
            default:
                return R.drawable.menu_hashtag;
        }
    }

    private static int[] typeColor(int type, boolean enabled) {
        if (!enabled) {
            return TILE_GRAY;
        }
        switch (type) {
            case SolegramFilterEngine.TYPE_REGEX:
                return TILE_PURPLE;
            case SolegramFilterEngine.TYPE_SENDER:
                return TILE_ORANGE;
            case SolegramFilterEngine.TYPE_FORWARD:
                return TILE_INDIGO;
            default:
                return TILE_RED;
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MASTER: {
                final boolean value = SolegramConfig.toggle("messageFilters");
                if (view instanceof TextCheckCell) {
                    final TextCheckCell cell = (TextCheckCell) view;
                    cell.setChecked(value);
                    cell.setBackgroundColorAnimated(value, Theme.getColor(value ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                }
                listView.adapter.update(true);
                return;
            }
            case ID_MODE_COLLAPSE:
            case ID_MODE_HIDE:
                SolegramConfig.putInt("messageFiltersMode", item.id == ID_MODE_HIDE ? SolegramMessageFilters.MODE_HIDE : SolegramMessageFilters.MODE_COLLAPSE);
                listView.adapter.update(true);
                return;
            case ID_PRESET_ADS:
                togglePreset(view, "filterAds");
                return;
            case ID_PRESET_JUNK:
                togglePreset(view, "filterJunk");
                return;
            case ID_PRESET_INVITES:
                togglePreset(view, "filterInvites");
                return;
            case ID_ADS_STRICT:
                togglePreset(view, "filterAdsStrict");
                return;
            case ID_HIDE_SPONSORED: {
                final boolean value = SolegramConfig.toggle("hideSponsored");
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                return;
            }
            case ID_ADD:
                presentFragment(SolegramFilterEditActivity.create());
                return;
            case ID_STATS_RESET:
                confirm(getString(R.string.SG_FiltersStatsReset), getString(R.string.SG_FiltersStatsResetConfirm), getString(R.string.SG_Reset), () -> {
                    SolegramMessageFilters.resetStats();
                    listView.adapter.update(true);
                });
                return;
            case ID_EXCLUDED:
                showExcluded();
                return;
            case ID_EXPORT:
                exportRules();
                return;
            case ID_IMPORT:
                importRules();
                return;
            default:
                if (item.id >= ID_RULE_BASE) {
                    final ArrayList<SolegramFilterEngine.Rule> rules = SolegramMessageFilters.getRules();
                    final int index = item.id - ID_RULE_BASE;
                    if (index < rules.size()) {
                        presentFragment(SolegramFilterEditActivity.edit(rules.get(index).id));
                    }
                }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < ID_RULE_BASE || getParentActivity() == null) {
            return false;
        }
        final ArrayList<SolegramFilterEngine.Rule> rules = SolegramMessageFilters.getRules();
        final int index = item.id - ID_RULE_BASE;
        if (index >= rules.size()) {
            return false;
        }
        final SolegramFilterEngine.Rule rule = rules.get(index);
        final CharSequence[] options = {
            getString(rule.enabled ? R.string.SG_FilterTurnOff : R.string.SG_FilterTurnOn),
            getString(R.string.SG_FilterEdit),
            getString(R.string.Delete)
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(SolegramMessageFilters.titleOf(rule));
        builder.setItems(options, (d, which) -> {
            if (which == 0) {
                SolegramMessageFilters.setRuleEnabled(rule, !rule.enabled);
                listView.adapter.update(true);
            } else if (which == 1) {
                presentFragment(SolegramFilterEditActivity.edit(rule.id));
            } else {
                confirm(getString(R.string.SG_FilterDelete), getString(R.string.SG_FilterDeleteConfirm), getString(R.string.Delete), () -> {
                    SolegramMessageFilters.deleteRule(rule.id);
                    listView.adapter.update(true);
                });
            }
        });
        showDialog(builder.create());
        return true;
    }

    private void togglePreset(View view, String key) {
        final boolean value = SolegramConfig.toggle(key);
        if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(value);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        if (value) {
            SolegramMessageFilters.turnOnIfOff();
        }
        listView.adapter.update(true);
    }

    private void showExcluded() {
        final ArrayList<Long> chats = SolegramMessageFilters.getExcludedChats();
        if (chats.isEmpty() || getParentActivity() == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_FiltersExcludedInfo)).show();
            return;
        }
        final CharSequence[] names = new CharSequence[chats.size()];
        for (int i = 0; i < chats.size(); i++) {
            final String name = SolegramMessageFilters.peerName(currentAccount, chats.get(i));
            names[i] = name != null ? name : String.valueOf(chats.get(i));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.SG_FiltersExcluded));
        builder.setItems(names, (d, which) -> {
            // tapping a chat gives it its filters back
            SolegramMessageFilters.setChatExcluded(chats.get(which), false);
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_FiltersExcludedRemoved, names[which])).show();
        });
        builder.setNegativeButton(getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private void exportRules() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "solegram");
            dir.mkdirs();
            final File file = new File(dir, "solegram-filters.json");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(SolegramMessageFilters.exportJson().getBytes(StandardCharsets.UTF_8));
            }
            final Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, getString(R.string.SG_FiltersExport)));
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_FiltersExportFailed)).show();
        }
    }

    private void importRules() {
        try {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        int count;
        try (java.io.InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(data.getData())) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int r;
            // a rules file is small; anything big is not one
            while (in != null && (r = in.read(buf)) > 0 && out.size() < 1024 * 1024) {
                out.write(buf, 0, r);
            }
            count = SolegramMessageFilters.importJson(out.toString("UTF-8"));
        } catch (Throwable e) {
            FileLog.e(e);
            count = -1;
        }
        if (count < 0) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_FiltersImportFailed)).show();
        } else {
            listView.adapter.update(true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_FiltersImported, count)).show();
        }
    }

    /** Shows one row (opened from the settings search). */
    public void highlightItem(int itemId) {
        if (listView == null) {
            pendingHighlight = itemId;
            return;
        }
        final int position = listView.findPositionByItemId(itemId);
        if (position < 0) {
            return;
        }
        listView.highlightRow(() -> {
            listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
            return position;
        });
    }

    private void confirm(String title, String message, String action, Runnable onConfirm) {
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action, (d, w) -> onConfirm.run())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final android.widget.TextView button = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    static UItem tile(UItem item, int[] color) {
        final int base = Theme.isCurrentThemeDark() ? color[1] : color[0];
        return item.setIconTile(androidx.core.graphics.ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
    }
}
