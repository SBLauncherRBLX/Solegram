/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Translating a message: first the language to translate it into. On top the usual one and the last ones picked,
 * then every language, with a search. The choice is remembered as the next default.
 */
public class SolegramLanguagePicker extends BottomSheet {

    private static final String PREFS = "solegram_translate";
    private static final int RECENT = 4;

    private final Utilities.Callback<String> onPicked;
    private final ArrayList<TranslateController.Language> all;
    private final ArrayList<Object> rows = new ArrayList<>();
    private final RecyclerListView list;
    private final String current;
    private String query = "";

    /** Shows the picker; {@code onPicked} gets the language code. */
    public static void show(BaseFragment fragment, String suggested, Utilities.Callback<String> onPicked) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new SolegramLanguagePicker(fragment, suggested, onPicked));
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ArrayList<String> recent() {
        final ArrayList<String> out = new ArrayList<>();
        final String s = prefs().getString("recent", "");
        if (!TextUtils.isEmpty(s)) {
            for (String c : s.split(",")) {
                if (!TextUtils.isEmpty(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static void remember(String code) {
        final ArrayList<String> r = recent();
        r.remove(code);
        r.add(0, code);
        while (r.size() > RECENT) {
            r.remove(r.size() - 1);
        }
        prefs().edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    private SolegramLanguagePicker(BaseFragment fragment, String suggested, Utilities.Callback<String> onPicked) {
        super(fragment.getParentActivity(), true, fragment.getResourceProvider());
        this.onPicked = onPicked;
        this.current = suggested;
        this.all = TranslateController.getLanguages();
        final Context context = fragment.getParentActivity();
        fixNavigationBar();

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(getString(R.string.SG_TranslateTo));
        title.setPadding(dp(22), dp(18), dp(22), dp(8));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout searchBox = new FrameLayout(context);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(getThemedColor(Theme.key_dialogTextBlack), 0.06f)));
        final EditTextBoldCursor search = new EditTextBoldCursor(context);
        search.setHint(getString(R.string.Search));
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        search.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(getThemedColor(Theme.key_dialogTextHint));
        search.setCursorColor(getThemedColor(Theme.key_dialogTextBlack));
        search.setPadding(dp(14), 0, dp(14), 0);
        searchBox.addView(search, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 16, 4, 16, 8));

        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new Adapter());
        list.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || !(rows.get(position) instanceof TranslateController.Language)) {
                return;
            }
            final String code = ((TranslateController.Language) rows.get(position)).code;
            remember(code);
            TranslateAlert2.setToLanguage(code);
            dismiss();
            onPicked.run(code);
        });
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.55f)));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                fill();
            }
        });
        fill();
        setCustomView(column);
    }

    private TranslateController.Language find(String code) {
        for (TranslateController.Language l : all) {
            if (l.code.equals(code)) {
                return l;
            }
        }
        return null;
    }

    private void fill() {
        rows.clear();
        if (query.isEmpty()) {
            final LinkedHashSet<String> top = new LinkedHashSet<>();
            if (current != null) {
                top.add(current);
            }
            top.addAll(recent());
            final ArrayList<TranslateController.Language> first = new ArrayList<>();
            for (String c : top) {
                final TranslateController.Language l = find(c);
                if (l != null) {
                    first.add(l);
                }
            }
            if (!first.isEmpty()) {
                rows.add(getString(R.string.SG_TranslateRecent));
                rows.addAll(first);
            }
            rows.add(getString(R.string.SG_TranslateAll));
            rows.addAll(all);
        } else {
            for (TranslateController.Language l : all) {
                if (l.q != null && l.q.contains(query) || l.code.startsWith(query)) {
                    rows.add(l);
                }
            }
        }
        if (list.getAdapter() != null) {
            list.getAdapter().notifyDataSetChanged();
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 1;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof TranslateController.Language ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final TextView tv = new TextView(parent.getContext());
            if (viewType == 1) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                tv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
                tv.setPadding(dp(22), dp(13), dp(22), dp(13));
                tv.setBackground(Theme.getSelectorDrawable(false));
            } else {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                tv.setTypeface(AndroidUtilities.bold());
                tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
                tv.setPadding(dp(22), dp(14), dp(22), dp(6));
            }
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final TextView tv = (TextView) holder.itemView;
            final Object row = rows.get(position);
            if (row instanceof TranslateController.Language) {
                final TranslateController.Language l = (TranslateController.Language) row;
                final boolean own = l.ownDisplayName != null && !l.ownDisplayName.equalsIgnoreCase(l.displayName);
                tv.setText(own ? l.displayName + "  ·  " + l.ownDisplayName : l.displayName);
            } else {
                tv.setText((CharSequence) row);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    /** The option, on by default: ask which language before translating. */
    public static boolean enabled() {
        return com.solegram.messenger.SolegramConfig.prefs().getBoolean("translatePickLanguage", true);
    }
}
