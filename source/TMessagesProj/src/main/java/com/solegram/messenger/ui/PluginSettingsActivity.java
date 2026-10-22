/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.solegram.messenger.plugins.PluginGuard;
import com.solegram.messenger.plugins.PluginHandle;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginSecurity;
import com.solegram.messenger.plugins.PluginSetting;
import com.solegram.messenger.plugins.PluginsController;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import static org.telegram.messenger.LocaleController.getString;

/** Renders a plugin settings page built by create_settings() (or a nested sub-page). */
public class PluginSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED = -100;
    private static final int ID_SEC_VERDICT = -200;
    private static final int ID_SEC_HASH = -201;
    private static final int ID_SEC_RECHECK = -202;
    /** -300 + category */
    private static final int ID_SEC_CATEGORY = -300;
    private static final int ID_BASE = 1000;

    private final String pluginId;
    private final List<PluginSetting> fixedItems;
    private final String fixedTitle;
    private List<PluginSetting> settings;
    private String settingsError;
    private final ArrayList<View> customViews = new ArrayList<>();

    public PluginSettingsActivity(String pluginId) {
        this(pluginId, null, null);
    }

    public PluginSettingsActivity(String pluginId, List<PluginSetting> subPage, String title) {
        this.pluginId = pluginId;
        this.fixedItems = subPage;
        this.fixedTitle = title;
    }

    private PluginMeta meta() {
        return PluginsController.getInstance().getPlugin(pluginId);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramPluginsChanged);
        loadSettings();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramPluginsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.solegramPluginsChanged && args.length > 0 && pluginId.equals(args[0])) {
            if (fixedItems == null) {
                loadSettings();
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
        }
    }

    private void loadSettings() {
        if (fixedItems != null) {
            settings = fixedItems;
            return;
        }
        settings = null;
        settingsError = null;
        PluginMeta meta = meta();
        PluginHandle handle = meta != null ? meta.handle : null;
        if (handle == null) {
            return;
        }
        try {
            settings = handle.createSettings();
        } catch (Throwable e) {
            FileLog.e(e);
            settingsError = e.getMessage();
        }
        customViews.clear();
        customItems.clear();
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
        if (fixedTitle != null) {
            return fixedTitle;
        }
        PluginMeta meta = meta();
        return meta != null ? meta.name : pluginId;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginMeta meta = meta();
        if (meta == null) {
            items.add(UItem.asShadow(getString(R.string.SG_PluginNotFound)));
            return;
        }
        if (fixedItems == null) {
            items.add(UItem.asRippleCheck(ID_ENABLED, getString(R.string.SG_PluginEnabled)).setChecked(meta.enabled));
            StringBuilder info = new StringBuilder();
            info.append("v").append(meta.version);
            if (!TextUtils.isEmpty(meta.author)) {
                info.append(" · ").append(meta.author);
            }
            if (!TextUtils.isEmpty(meta.description)) {
                info.append("\n\n").append(PluginCell.stripMarkdown(meta.description));
            }
            if (!TextUtils.isEmpty(meta.error)) {
                info.append("\n\n").append(LocaleController.formatString(R.string.SG_PluginError, meta.error));
            }
            items.add(UItem.asShadow(info.toString()));
            if (!meta.loaded) {
                addSecurity(items, meta);
                return;
            }
            if (settingsError != null) {
                items.add(UItem.asShadow(LocaleController.formatString(R.string.SG_PluginError, settingsError)));
                addSecurity(items, meta);
                return;
            }
            if (settings == null || settings.isEmpty()) {
                items.add(UItem.asShadow(getString(R.string.SG_PluginNoSettings)));
                addSecurity(items, meta);
                return;
            }
        }
        if (settings == null) {
            return;
        }
        boolean needShadow = false;
        for (int i = 0; i < settings.size(); i++) {
            PluginSetting s = settings.get(i);
            int id = ID_BASE + i;
            if (s == null || s.type == null) {
                continue;
            }
            switch (s.type) {
                case PluginSetting.HEADER:
                    if (needShadow) {
                        items.add(UItem.asShadow(null));
                    }
                    items.add(UItem.asHeader(s.text));
                    needShadow = false;
                    break;
                case PluginSetting.DIVIDER:
                    items.add(UItem.asShadow(TextUtils.isEmpty(s.text) ? null : s.text));
                    needShadow = false;
                    break;
                case PluginSetting.SWITCH: {
                    boolean value = asBoolean(readValue(s), s.defaultValue);
                    UItem item = TextUtils.isEmpty(s.subtext)
                        ? UItem.asCheck(id, s.text)
                        : UItem.asButtonCheck(id, s.text, s.subtext);
                    items.add(item.setChecked(value));
                    needShadow = true;
                    break;
                }
                case PluginSetting.SELECTOR: {
                    int index = asInt(readValue(s), s.defaultValue);
                    String value = s.items != null && index >= 0 && index < s.items.length ? s.items[index] : "";
                    items.add(UItem.asButton(id, s.getIconResId(), s.text, value));
                    needShadow = true;
                    break;
                }
                case PluginSetting.INPUT: {
                    String value = asString(readValue(s), s.defaultValue);
                    UItem item = UItem.asButton(id, s.getIconResId(), s.text, value);
                    items.add(item);
                    needShadow = true;
                    break;
                }
                case PluginSetting.TEXT: {
                    UItem item = TextUtils.isEmpty(s.subtext)
                        ? UItem.asButton(id, s.getIconResId(), s.text)
                        : UItem.asButton(id, s.getIconResId(), s.text, s.subtext);
                    if (s.red) {
                        item.red();
                    } else if (s.accent) {
                        item.accent();
                    }
                    items.add(item);
                    needShadow = true;
                    break;
                }
                case PluginSetting.EDIT_TEXT:
                    items.add(UItem.asCustom(id, createEditText(s)));
                    needShadow = true;
                    break;
                case PluginSetting.CUSTOM: {
                    UItem item = null;
                    if (s.uitem instanceof UItem) {
                        item = (UItem) s.uitem;
                    } else if (s.view != null) {
                        AndroidUtilities.removeFromParent(s.view);
                        item = UItem.asCustom(id, s.view);
                    } else if (s.factory != null) {
                        item = getCustomItem(i, id, s);
                    }
                    if (item != null) {
                        items.add(item);
                        needShadow = true;
                    }
                    break;
                }
            }
        }
        if (needShadow) {
            items.add(UItem.asShadow(null));
        }
        if (fixedItems == null) {
            addSecurity(items, meta);
        }
    }

    /** Solegram: the security report of the installed file, after the plugin's own settings. */
    private void addSecurity(ArrayList<UItem> items, PluginMeta meta) {
        PluginSecurity.Report report = meta.report;
        if (report == null) {
            return;
        }
        boolean blocklisted = meta.blocked == PluginGuard.BLOCK_LIST;
        items.add(UItem.asHeader(getString(R.string.SG_SecSection)));
        String reason = blocklisted ? getString(R.string.SG_SecStatusBlocklisted)
            : report.reason != null && report.verdict != PluginSecurity.VERDICT_SAFE ? PluginSecuritySheet.ruleLabel(report.reason)
            : getString(report.findings.isEmpty() ? R.string.SG_SecNoPermissions : R.string.SG_SecSummarySafe);
        items.add(PluginSecuritySheet.tile(UItem.asButton(ID_SEC_VERDICT, PluginSecuritySheet.verdictIcon(report.verdict, blocklisted),
            PluginSecuritySheet.verdictTitle(report.verdict, blocklisted)), PluginSecuritySheet.verdictColors(report.verdict, blocklisted))
            .setSolegramSubtitle(reason));
        for (int category : report.categories()) {
            int severity = report.categorySeverity(category);
            CharSequence title = PluginSecuritySheet.categoryTitle(category);
            UItem row = severity >= PluginSecurity.HIGH
                ? UItem.asButton(ID_SEC_CATEGORY + category, PluginSecuritySheet.categoryIcon(category), title,
                    getString(severity >= PluginSecurity.CRITICAL ? R.string.SG_SecPillCritical : R.string.SG_SecPillHigh))
                : UItem.asButton(ID_SEC_CATEGORY + category, PluginSecuritySheet.categoryIcon(category), title);
            items.add(PluginSecuritySheet.tile(row, PluginSecuritySheet.categoryColors(category))
                .setSolegramSubtitle(PluginSecuritySheet.categoryRules(report, category)));
        }
        String sha = report.sha256 != null ? report.sha256 : "";
        String shortHash = sha.length() == 64 ? sha.substring(0, 8) + "…" + sha.substring(56) : sha;
        items.add(UItem.asButton(ID_SEC_HASH, R.drawable.msg_copy, getString(R.string.SG_SecHash), shortHash));
        items.add(UItem.asButton(ID_SEC_RECHECK, R.drawable.msg_retry, getString(R.string.SG_SecRecheck)).accent());
        items.add(UItem.asShadow(getString(R.string.SG_SecCheckedOnDevice)));
    }

    private void onSecurityClick(UItem item) {
        PluginMeta meta = meta();
        if (meta == null || meta.report == null) {
            return;
        }
        if (item.id == ID_SEC_HASH) {
            AndroidUtilities.addToClipboard(meta.report.sha256);
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.SG_SecHashCopied)).show();
        } else if (item.id == ID_SEC_RECHECK) {
            PluginsController.getInstance().rescan(pluginId, m -> {
                if (listView != null) {
                    listView.adapter.update(true);
                }
                if (m.report != null) {
                    String verdict = PluginSecuritySheet.verdictTitle(m.report.verdict, m.blocked == PluginGuard.BLOCK_LIST);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_SecRechecked, verdict)).show();
                }
            });
        } else {
            showDialog(new PluginSecuritySheet(this, meta, meta.report, null, meta.blocked == PluginGuard.BLOCK_LIST, PluginSecuritySheet.MODE_VIEW, null));
        }
    }

    private final ArrayList<UItem> customItems = new ArrayList<>();

    /** The adapter wraps custom rows in a container; factories expect the view they created. */
    private View customViewAt(int index, View fallback) {
        View v = index >= 0 && index < customViews.size() ? customViews.get(index) : null;
        return v != null ? v : fallback;
    }

    private UItem getCustomItem(int index, int id, PluginSetting s) {
        while (customViews.size() <= index) {
            customViews.add(null);
            customItems.add(null);
        }
        View v = customViews.get(index);
        UItem item = customItems.get(index);
        if (v == null) {
            try {
                v = s.factory.createView(getContext(), listView, currentAccount, classGuid, getResourceProvider());
                if (v != null) {
                    item = s.shadow ? UItem.asCustomShadow(v) : UItem.asCustom(id, v);
                    item.id = id;
                    item.object = s.factoryArgs;
                    item.object2 = s;
                    s.factory.bindView(v, item, false, listView != null ? listView.adapter : null, listView);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            customViews.set(index, v);
            customItems.set(index, item);
        }
        if (v != null) {
            AndroidUtilities.removeFromParent(v);
        }
        return item;
    }

    private View createEditText(PluginSetting s) {
        Context ctx = getContext();
        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setHintText(s.hint);
        field.setBackground(null);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        if (s.multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setSingleLine(false);
            field.setMaxLines(12);
        } else {
            field.setSingleLine(true);
        }
        if (s.maxLength > 0) {
            field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(s.maxLength)});
        }
        field.setText(asString(readValue(s), s.defaultValue));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String text = e.toString();
                if (!TextUtils.isEmpty(s.mask) && !text.isEmpty()) {
                    try {
                        if (!text.matches(s.mask)) {
                            return;
                        }
                    } catch (Exception ignore) {
                    }
                }
                writeValue(s, JSONObject.quote(text));
                fire(s, text);
            }
        });
        frame.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 10, 21, 10));
        return frame;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLED) {
            PluginMeta meta = meta();
            if (meta == null) {
                return;
            }
            boolean enable = !meta.enabled;
            Runnable apply = () -> {
                PluginsController.getInstance().setEnabled(pluginId, enable);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enable);
                    ((TextCheckCell) view).setBackgroundColorAnimated(enable, Theme.getColor(enable ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                }
            };
            if (enable) {
                // Solegram: verified (and, if never reviewed, shown) before its code runs
                PluginSecuritySheet.confirmEnable(this, meta, apply);
            } else {
                apply.run();
            }
            return;
        }
        if (item.id <= ID_SEC_VERDICT && item.id > ID_SEC_CATEGORY - 100) {
            onSecurityClick(item);
            return;
        }
        int index = item.id - ID_BASE;
        if (settings == null || index < 0 || index >= settings.size()) {
            return;
        }
        PluginSetting s = settings.get(index);
        try {
            switch (s.type) {
                case PluginSetting.SWITCH: {
                    boolean value = !asBoolean(readValue(s), s.defaultValue);
                    writeValue(s, value ? "true" : "false");
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(value);
                    } else if (view instanceof NotificationsCheckCell) {
                        ((NotificationsCheckCell) view).setChecked(value);
                    }
                    fire(s, value);
                    break;
                }
                case PluginSetting.SELECTOR: {
                    if (s.items == null || s.items.length == 0) {
                        return;
                    }
                    int current = asInt(readValue(s), s.defaultValue);
                    showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), s.items, s.text, current, (d, which) -> {
                        writeValue(s, String.valueOf(which));
                        fire(s, which);
                        listView.adapter.update(true);
                    }));
                    break;
                }
                case PluginSetting.INPUT:
                    showInputDialog(s);
                    break;
                case PluginSetting.TEXT:
                    if (s.onClick != null) {
                        s.onClick.onClick(view);
                    }
                    if (s.subPage != null) {
                        List<PluginSetting> sub = s.subPage.create();
                        if (sub != null) {
                            presentFragment(new PluginSettingsActivity(pluginId, sub, s.text));
                        }
                    }
                    break;
                case PluginSetting.CUSTOM:
                    if (s.factory != null && s.factory.isClickable()) {
                        s.factory.onClick(customViewAt(index, view));
                    } else if (s.onClick != null) {
                        s.onClick.onClick(view);
                    }
                    if (s.subPage != null) {
                        List<PluginSetting> sub = s.subPage.create();
                        if (sub != null) {
                            presentFragment(new PluginSettingsActivity(pluginId, sub, s.text != null ? s.text : getTitle().toString()));
                        }
                    }
                    break;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.SG_PluginError, e.getMessage())).show();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        int index = item.id - ID_BASE;
        if (settings == null || index < 0 || index >= settings.size()) {
            return false;
        }
        PluginSetting s = settings.get(index);
        try {
            if (s.factory != null) {
                return s.factory.onLongClick(customViewAt(index, view));
            }
            if (s.onLongClick != null) {
                return s.onLongClick.onLongClick(view);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private void showInputDialog(PluginSetting s) {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        field.setText(asString(readValue(s), s.defaultValue));
        field.setSelection(field.length());
        FrameLayout container = new FrameLayout(ctx);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 6));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, getResourceProvider());
        builder.setTitle(s.text);
        if (!TextUtils.isEmpty(s.subtext)) {
            builder.setMessage(s.subtext);
        }
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            String text = field.getText() == null ? "" : field.getText().toString();
            writeValue(s, JSONObject.quote(text));
            fire(s, text);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 200);
    }

    // region values

    private Object readValue(PluginSetting s) {
        if (TextUtils.isEmpty(s.key)) {
            return null;
        }
        String json = PluginsController.getSettingJson(pluginId, s.key);
        if (json == null) {
            return null;
        }
        try {
            return new JSONTokener(json).nextValue();
        } catch (Exception e) {
            return json;
        }
    }

    private void writeValue(PluginSetting s, String json) {
        if (!TextUtils.isEmpty(s.key)) {
            PluginsController.setSettingJson(pluginId, s.key, json);
        }
    }

    private void fire(PluginSetting s, Object value) {
        if (s.onChange != null) {
            try {
                s.onChange.onChange(value);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private static boolean asBoolean(Object v, Object def) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (def instanceof Boolean) {
            return (Boolean) def;
        }
        return false;
    }

    private static int asInt(Object v, Object def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (def instanceof Number) {
            return ((Number) def).intValue();
        }
        return 0;
    }

    private static String asString(Object v, Object def) {
        if (v instanceof String) {
            return (String) v;
        }
        if (v != null && v != JSONObject.NULL) {
            return String.valueOf(v);
        }
        return def != null ? String.valueOf(def) : "";
    }
    // endregion
}
