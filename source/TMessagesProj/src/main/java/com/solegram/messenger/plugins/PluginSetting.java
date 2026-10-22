/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.view.View;

import java.util.List;

/** Java side of ui.settings dataclasses (Header, Switch, Selector, Input, Text, EditText, Divider, Custom). */
public class PluginSetting {
    public static final String HEADER = "header";
    public static final String DIVIDER = "divider";
    public static final String SWITCH = "switch";
    public static final String SELECTOR = "selector";
    public static final String INPUT = "input";
    public static final String TEXT = "text";
    public static final String EDIT_TEXT = "edit_text";
    public static final String CUSTOM = "custom";

    public interface ValueCallback {
        void onChange(Object value);
    }

    public interface ViewCallback {
        void onClick(View view);
    }

    public interface LongClickCallback {
        boolean onLongClick(View view);
    }

    public interface SubPage {
        List<PluginSetting> create();
    }

    /** Builds the row view for Custom(factory=...) settings. */
    public interface ViewFactory {
        View createView(android.content.Context context, View listView, int account, int classGuid, Object resourcesProvider);

        void bindView(View view, Object item, boolean divider, Object adapter, View listView);

        boolean isClickable();

        void onClick(View view);

        boolean onLongClick(View view);
    }

    public String type;
    public String key;
    public String text;
    public String subtext;
    public String icon;
    public Object defaultValue;
    public String[] items;
    public boolean accent;
    public boolean red;
    public String hint;
    public boolean multiline;
    public int maxLength;
    public String mask;
    public String linkAlias;

    public ValueCallback onChange;
    public ViewCallback onClick;
    public LongClickCallback onLongClick;
    public SubPage subPage;

    // Custom rows
    public Object uitem;
    public View view;
    public ViewFactory factory;
    public Object factoryArgs;
    public boolean shadow;

    public int getIconResId() {
        return PluginsController.resolveDrawable(icon);
    }
}
