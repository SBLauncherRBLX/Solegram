/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramGlass;
import com.solegram.messenger.SolegramMeaning;
import com.solegram.messenger.SolegramMeaningSearch;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Results of a search by meaning: the question (editable), what was searched for as chips (a tap removes one, "+"
 * adds a word), who understood the question, "Refine with AI", and the found messages in Telegram's own search
 * rows with the found words highlighted. See {@link SolegramMeaningSearch} for how the search works.
 */
public class SolegramMeaningSearchActivity extends BaseFragment implements SolegramMeaningSearch.Listener {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SECTION = 1;
    private static final int TYPE_RESULT = 2;
    private static final int TYPE_LOADING = 3;
    private static final int TYPE_EMPTY = 4;
    private static final int TYPE_FOOTER = 5;
    private static final long DEBOUNCE_MS = 400;

    private final String initialQuery;
    private SolegramMeaningSearch search;

    private RecyclerListView listView;
    private Adapter adapter;
    private LinearLayout header;
    private FrameLayout fieldBar;
    private EditText field;
    private ImageView clearButton;
    private ChipFlow chips;
    private TextView searchedLabel;
    private RadialProgressView progress;
    private TextView status;
    private TextView refineButton;
    private TextView refineInfo;
    private TextView notice;
    private final LinkedHashMap<String, TextView> chipViews = new LinkedHashMap<>();

    @Nullable
    private SolegramMeaning.Plan plan;
    private int source;
    private boolean nanoWorking;
    @Nullable
    private String personChat;
    private ArrayList<SolegramMeaningSearch.Result> results = new ArrayList<>();
    private boolean searching;
    private boolean reranked;
    private boolean refining;
    private final ArrayList<Integer> rows = new ArrayList<>();

    private final Runnable debounced = this::runSearch;

    public SolegramMeaningSearchActivity(String query) {
        super();
        initialQuery = query == null ? "" : query;
    }

    @Override
    public boolean onFragmentCreate() {
        search = new SolegramMeaningSearch(currentAccount, this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(debounced);
        if (search != null) {
            search.cancel();
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_MeaningSearch));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        header = buildHeader(context);

        listView = new RecyclerListView(context, resourceProvider);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(16));
        listView.setItemAnimator(SolegramMotion.itemAnimator());
        listView.setAdapter(adapter = new Adapter());
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || rows.get(position) != TYPE_RESULT) {
                return;
            }
            final int index = position - firstResultRow();
            if (index >= 0 && index < results.size()) {
                openResult(results.get(index));
            }
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    AndroidUtilities.hideKeyboard(field);
                }
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 60, 0, 0));
        frame.addView(fieldBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 14, 8, 14, 0));

        field.setText(initialQuery);
        field.setSelection(field.length());
        AndroidUtilities.cancelRunOnUIThread(debounced);
        updateRows(false);
        runSearch();
        return fragmentView;
    }

    // region Header

    private LinearLayout buildHeader(Context context) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // the question, in a field of glass
        final FrameLayout fieldBox = new FrameLayout(context);
        fieldBox.setBackground(new GlassPill(getThemedColor(Theme.key_windowBackgroundGray), dp(22)));
        final TextView star = new TextView(context);
        star.setText("✦");
        star.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        star.setTypeface(AndroidUtilities.bold());
        star.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
        fieldBox.addView(star, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 0, 0, 0));
        field = new EditTextBoldCursor(context);
        field.setBackground(null);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        ((EditTextBoldCursor) field).setCursorColor(getThemedColor(Theme.key_featuredStickers_addButton));
        ((EditTextBoldCursor) field).setCursorWidth(1.5f);
        field.setHint(getString(R.string.SG_MeaningHint));
        field.setSingleLine(true);
        field.setPadding(0, 0, 0, 0);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                AndroidUtilities.cancelRunOnUIThread(debounced);
                runSearch();
                AndroidUtilities.hideKeyboard(field);
                return true;
            }
            return false;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                SolegramMotion.setVisible(clearButton, s.length() > 0, true);
                // a pause in typing starts the search; what was running is cancelled by the next one
                AndroidUtilities.cancelRunOnUIThread(debounced);
                AndroidUtilities.runOnUIThread(debounced, DEBOUNCE_MS);
            }
        });
        fieldBox.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 40, 0, 44, 0));
        clearButton = new ImageView(context);
        clearButton.setImageResource(R.drawable.ic_close_white);
        clearButton.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        clearButton.setScaleType(ImageView.ScaleType.CENTER);
        clearButton.setContentDescription(getString(R.string.ClearButton));
        clearButton.setOnClickListener(v -> {
            field.setText("");
            AndroidUtilities.showKeyboard(field);
        });
        SolegramMotion.pressable(clearButton, SolegramMotion.PRESS_BUTTON);
        clearButton.setVisibility(initialQuery.isEmpty() ? View.GONE : View.VISIBLE);
        fieldBox.addView(clearButton, LayoutHelper.createFrame(44, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));
        // the field stays above the list: results that keep arriving never take the focus (and the keyboard) away
        fieldBar = fieldBox;

        // what was searched for
        searchedLabel = new TextView(context);
        searchedLabel.setText(getString(R.string.SG_MeaningSearched));
        searchedLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        searchedLabel.setTypeface(AndroidUtilities.bold());
        searchedLabel.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        box.addView(searchedLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 2, 0, 0));
        chips = new ChipFlow(context);
        box.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // who understood the question, and whether it is still working
        final LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new RadialProgressView(context, resourceProvider);
        progress.setSize(dp(14));
        progress.setStrokeWidth(1.5f);
        progress.setProgressColor(getThemedColor(Theme.key_featuredStickers_addButton));
        statusRow.addView(progress, LayoutHelper.createLinear(18, 18, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        status.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        statusRow.addView(status, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        box.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 12, 0, 0));

        // the question (only the question) to the AI service, on a tap
        refineButton = new TextView(context);
        refineButton.setText("✦ " + getString(R.string.SG_MeaningRefine));
        refineButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        refineButton.setTypeface(AndroidUtilities.bold());
        refineButton.setGravity(Gravity.CENTER);
        refineButton.setPadding(dp(16), 0, dp(16), 0);
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        refineButton.setTextColor(accent);
        refineButton.setBackground(new GlassPill(ColorUtils.setAlphaComponent(accent, 0x22), dp(17)));
        refineButton.setOnClickListener(v -> refine());
        SolegramMotion.pressable(refineButton, SolegramMotion.PRESS_BUTTON);
        box.addView(refineButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, Gravity.LEFT, 0, 12, 0, 0));
        refineInfo = new TextView(context);
        refineInfo.setText(getString(R.string.SG_MeaningRefineInfo));
        refineInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        refineInfo.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        box.addView(refineInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 6, 0, 0));

        notice = new TextView(context);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        notice.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        notice.setVisibility(View.GONE);
        box.addView(notice, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 10, 0, 0));

        updateHeader(false);
        return box;
    }

    private void runSearch() {
        if (field == null) {
            return;
        }
        final String q = field.getText().toString().trim();
        searching = !q.isEmpty();
        refining = false;
        search.search(q);
        updateHeader(true);
        updateRows(true);
    }

    private void refine() {
        if (refining) {
            return;
        }
        refining = true;
        updateHeader(true);
        search.refineWithAi(ok -> {
            refining = false;
            updateHeader(true);
        });
    }

    /** Chips, status line and the refine button from the current plan. */
    private void updateHeader(boolean animated) {
        if (header == null) {
            return;
        }
        if (animated) {
            SolegramMotion.beginLayoutTransition(header);
        }
        final ArrayList<String> keys = new ArrayList<>();
        final ArrayList<TextView> views = new ArrayList<>();
        if (plan != null) {
            for (SolegramMeaning.Term t : plan.terms) {
                final String key = "t:" + t.query;
                keys.add(key);
                views.add(chip(key, t.display, t.isOriginal() ? CHIP_MAIN : t.concept < 0 ? CHIP_EXTRA : CHIP_RELATED, () -> {
                    removeChip(key);
                    search.removeTerm(t.query);
                }));
            }
            if (plan.hasDate()) {
                final String key = "d:" + plan.dateFrom;
                keys.add(key);
                views.add(chip(key, "📅 " + plan.dateLabel, CHIP_META, () -> {
                    removeChip(key);
                    search.removeDate();
                }));
            }
            if (plan.person != null) {
                final String key = "p:" + plan.person;
                keys.add(key);
                final String label = personChat != null ? LocaleController.formatString(R.string.SG_MeaningInChat, personChat) : plan.personLabel;
                views.add(chip(key, "👤 " + label, CHIP_META, () -> {
                    removeChip(key);
                    search.removePerson();
                }));
            }
            if (!plan.question.isEmpty()) {
                keys.add("add");
                views.add(chip("add", "+ " + getString(R.string.SG_MeaningAddWord), CHIP_ADD, this::askWord));
            }
        }
        chips.removeAllViews();
        for (TextView v : views) {
            chips.addView(v);
        }
        chipViews.keySet().retainAll(keys);
        final boolean hasPlan = plan != null && !plan.question.isEmpty();
        searchedLabel.setVisibility(hasPlan ? View.VISIBLE : View.GONE);
        chips.setVisibility(hasPlan ? View.VISIBLE : View.GONE);

        final String statusText;
        if (!hasPlan) {
            statusText = null;
        } else if (nanoWorking) {
            statusText = getString(R.string.SG_MeaningSourceNanoWorking);
        } else if (source == SolegramMeaningSearch.SOURCE_NANO) {
            statusText = getString(R.string.SG_MeaningSourceNano);
        } else if (source == SolegramMeaningSearch.SOURCE_AI) {
            statusText = getString(R.string.SG_MeaningSourceAi);
        } else {
            statusText = getString(R.string.SG_MeaningSourceBuiltin);
        }
        status.setText(statusText);
        ((View) status.getParent()).setVisibility(statusText == null ? View.GONE : View.VISIBLE);
        progress.setVisibility(searching || nanoWorking || refining ? View.VISIBLE : View.INVISIBLE);

        final boolean refineShown = hasPlan && SolegramMeaningSearch.canRefineWithAi() && source == SolegramMeaningSearch.SOURCE_BUILTIN && !nanoWorking;
        refineButton.setVisibility(refineShown ? View.VISIBLE : View.GONE);
        refineInfo.setVisibility(refineShown ? View.VISIBLE : View.GONE);
        refineButton.setEnabled(!refining);
        refineButton.setAlpha(refining ? 0.5f : 1f);
    }

    private void removeChip(String key) {
        final TextView v = chipViews.remove(key);
        if (v != null) {
            SolegramMotion.beginLayoutTransition(header);
            chips.removeView(v);
        }
        SolegramMotion.haptic(chips);
    }

    private void askWord() {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        input.setCursorColor(getThemedColor(Theme.key_featuredStickers_addButton));
        input.setSingleLine(true);
        input.setHint(getString(R.string.SG_MeaningAddWord));
        input.setBackground(null);
        input.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField), getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated), getThemedColor(Theme.key_text_RedRegular));
        input.setPadding(0, dp(6), 0, dp(6));
        final FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));
        final AlertDialog dialog = new AlertDialog.Builder(context, resourceProvider)
            .setTitle(getString(R.string.SG_MeaningAddWordTitle))
            .setView(container)
            .setPositiveButton(getString(R.string.Add), (d, w) -> {
                final String word = input.getText().toString().trim();
                if (!word.isEmpty()) {
                    search.addWord(word);
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 150));
        showDialog(dialog);
    }

    private static final int CHIP_MAIN = 0;
    private static final int CHIP_RELATED = 1;
    private static final int CHIP_EXTRA = 2;
    private static final int CHIP_META = 3;
    private static final int CHIP_ADD = 4;

    /** A chip: kept between updates (so a removed neighbour lets it glide, not blink). */
    private TextView chip(String key, String text, int kind, Runnable onTap) {
        TextView v = chipViews.get(key);
        if (v == null) {
            v = new TextView(getContext());
            v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(AndroidUtilities.displaySize.x - dp(48));
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setPadding(dp(12), 0, dp(kind == CHIP_ADD ? 12 : 10), 0);
            SolegramMotion.pressable(v, SolegramMotion.PRESS_BUTTON);
            chipViews.put(key, v);
        }
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        final int textColor;
        final int fillColor;
        switch (kind) {
            case CHIP_MAIN:
                textColor = accent;
                fillColor = ColorUtils.setAlphaComponent(accent, 0x26);
                v.setTypeface(AndroidUtilities.bold());
                break;
            case CHIP_EXTRA:
                textColor = accent;
                fillColor = ColorUtils.setAlphaComponent(accent, 0x14);
                v.setTypeface(null);
                break;
            case CHIP_ADD:
                textColor = accent;
                fillColor = 0;
                v.setTypeface(AndroidUtilities.bold());
                break;
            default:
                textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
                fillColor = getThemedColor(Theme.key_windowBackgroundGray);
                v.setTypeface(kind == CHIP_META ? AndroidUtilities.bold() : null);
                break;
        }
        v.setTextColor(textColor);
        final GlassPill bg = new GlassPill(fillColor, dp(15));
        if (kind == CHIP_ADD) {
            bg.outline(ColorUtils.setAlphaComponent(accent, 0x66));
        }
        v.setBackground(bg);
        if (kind == CHIP_ADD) {
            v.setText(text);
        } else {
            // the cross that removes it, quieter than the word
            final SpannableStringBuilder sb = new SpannableStringBuilder(text).append("  ✕");
            sb.setSpan(new ForegroundColorSpan(ColorUtils.setAlphaComponent(textColor, 0x80)), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            v.setText(sb);
        }
        v.setContentDescription(text);
        v.setOnClickListener(x -> onTap.run());
        return v;
    }

    // endregion

    // region Results

    @Override
    public void onPlan(SolegramMeaning.Plan plan, int source, boolean nanoWorking, @Nullable String personChat) {
        this.plan = plan;
        this.source = source;
        this.nanoWorking = nanoWorking;
        this.personChat = personChat;
        updateHeader(true);
    }

    @Override
    public void onResults(ArrayList<SolegramMeaningSearch.Result> results, boolean searching, boolean reranked) {
        this.results = results;
        this.searching = searching;
        this.reranked = reranked;
        for (SolegramMeaningSearch.Result r : results) {
            SolegramMeaningSearch.applyHighlight(r);
        }
        if (progress != null) {
            progress.setVisibility(searching || nanoWorking || refining ? View.VISIBLE : View.INVISIBLE);
        }
        updateRows(true);
    }

    @Override
    public void onNotice(@Nullable String text) {
        if (notice == null) {
            return;
        }
        notice.setText(text);
        SolegramMotion.setVisible(notice, !TextUtils.isEmpty(text), true);
    }

    private int firstResultRow() {
        return rows.indexOf(TYPE_RESULT);
    }

    private void updateRows(boolean animated) {
        rows.clear();
        rows.add(TYPE_HEADER);
        final boolean asked = plan != null && !plan.question.isEmpty();
        if (!results.isEmpty()) {
            rows.add(TYPE_SECTION);
            for (int i = 0; i < results.size(); i++) {
                rows.add(TYPE_RESULT);
            }
        } else if (searching) {
            rows.add(TYPE_LOADING);
            rows.add(TYPE_LOADING);
            rows.add(TYPE_LOADING);
        } else if (asked) {
            rows.add(TYPE_EMPTY);
        }
        rows.add(TYPE_FOOTER);
        if (adapter != null) {
            // stable ids: results that move up or down glide there, new ones fade in
            adapter.notifyDataSetChanged();
        }
    }

    private void openResult(SolegramMeaningSearch.Result r) {
        final MessageObject m = r.message;
        final long did = m.getDialogId();
        final Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        if (!DialogObject.isEncryptedDialog(did)) {
            args.putInt("message_id", m.getId());
        }
        if (!getMessagesController().checkCanOpenChat(args, this)) {
            return;
        }
        AndroidUtilities.hideKeyboard(field);
        presentFragment(new ChatActivity(args));
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        Adapter() {
            setHasStableIds(true);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_RESULT;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            final int type = rows.get(position);
            if (type == TYPE_RESULT) {
                final int index = position - firstResultRow();
                if (index >= 0 && index < results.size()) {
                    final MessageObject m = results.get(index).message;
                    return 1000 + (((long) m.getId()) * 1_000_003L ^ m.getDialogId());
                }
            }
            if (type == TYPE_LOADING) {
                return 100 + position;
            }
            return type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    if (header.getParent() != null) {
                        ((ViewGroup) header.getParent()).removeView(header);
                    }
                    view = header;
                    break;
                case TYPE_SECTION:
                    view = new GraySectionCell(context, resourceProvider);
                    break;
                case TYPE_RESULT:
                    view = new DialogCell(null, context, false, true) {
                        @Override
                        public boolean isForumCell() {
                            return false;
                        }
                    };
                    break;
                case TYPE_LOADING: {
                    final FlickerLoadingView flicker = new FlickerLoadingView(context, resourceProvider);
                    flicker.setViewType(FlickerLoadingView.DIALOG_TYPE);
                    flicker.setIsSingleCell(true);
                    view = flicker;
                    break;
                }
                case TYPE_EMPTY: {
                    final LinearLayout empty = new LinearLayout(context);
                    empty.setOrientation(LinearLayout.VERTICAL);
                    empty.setGravity(Gravity.CENTER_HORIZONTAL);
                    empty.setPadding(dp(32), dp(36), dp(32), dp(24));
                    final TextView title = new TextView(context);
                    title.setText(getString(R.string.SG_MeaningEmpty));
                    title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                    title.setTypeface(AndroidUtilities.bold());
                    title.setGravity(Gravity.CENTER);
                    title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    empty.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                    final TextView sub = new TextView(context);
                    sub.setText(getString(R.string.SG_MeaningEmptyInfo));
                    sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    sub.setGravity(Gravity.CENTER);
                    sub.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                    empty.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
                    view = empty;
                    break;
                }
                default: {
                    final TextView footer = new TextView(context);
                    footer.setText(getString(R.string.SG_MeaningHow));
                    footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
                    footer.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                    footer.setPadding(dp(18), dp(14), dp(18), dp(10));
                    view = footer;
                    break;
                }
            }
            if (view.getLayoutParams() == null) {
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final RecyclerListView.Holder holder = new RecyclerListView.Holder(view);
            if (viewType == TYPE_HEADER) {
                // one header with the field the user types in: never recycled into another row
                holder.setIsRecyclable(false);
            }
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_SECTION: {
                    final GraySectionCell cell = (GraySectionCell) holder.itemView;
                    cell.setText(getString(R.string.SG_MeaningBest), reranked ? getString(R.string.SG_MeaningReranked) : null, null);
                    break;
                }
                case TYPE_RESULT: {
                    final int index = position - firstResultRow();
                    if (index < 0 || index >= results.size()) {
                        return;
                    }
                    final MessageObject m = results.get(index).message;
                    final DialogCell cell = (DialogCell) holder.itemView;
                    cell.useSeparator = index != results.size() - 1;
                    cell.setDialog(m.getDialogId(), m, m.messageOwner.date, false, false);
                    break;
                }
            }
        }
    }

    // endregion

    /**
     * A rounded fill with the light edge of Solegram's glass (brighter on top, fading downwards), for the question field
     * and the chips. Nothing is allocated while drawing.
     */
    private static final class GlassPill extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private int shaderHeight;

        GlassPill(int color, float radius) {
            this.radius = radius;
            fill.setColor(color);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1, dp(0.66f)));
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(Math.max(1, dp(1)));
            outline.setColor(0);
        }

        void outline(int color) {
            outline.setColor(color);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            rect.set(b);
            final float r = Math.min(radius, rect.height() / 2f);
            if (fill.getColor() >>> 24 > 0) {
                canvas.drawRoundRect(rect, r, r, fill);
            }
            if (outline.getColor() >>> 24 > 0) {
                final float inset = outline.getStrokeWidth() / 2f;
                rect.inset(inset, inset);
                canvas.drawRoundRect(rect, r, r, outline);
                return;
            }
            if (shaderHeight != b.height()) {
                shaderHeight = b.height();
                final boolean dark = Theme.isCurrentThemeDark();
                rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, SolegramGlass.menuRimTop(dark), SolegramGlass.menuRimBottom(dark), Shader.TileMode.CLAMP));
            }
            final float inset = rim.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            canvas.drawRoundRect(rect, r, r, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Lays chips out in lines, as text wraps. */
    @SuppressLint("ViewConstructor")
    private static final class ChipFlow extends ViewGroup {
        private final int gap = dp(6);
        private final int chipHeight = dp(30);

        ChipFlow(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            int x = 0, y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View c = getChildAt(i);
                if (c.getVisibility() == GONE) {
                    continue;
                }
                c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(chipHeight, MeasureSpec.EXACTLY));
                if (x > 0 && x + c.getMeasuredWidth() > width) {
                    x = 0;
                    y += chipHeight + gap;
                }
                x += c.getMeasuredWidth() + gap;
            }
            setMeasuredDimension(width, getChildCount() == 0 ? 0 : y + chipHeight);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int width = r - l;
            final boolean rtl = LocaleController.isRTL;
            int x = 0, y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                final View c = getChildAt(i);
                if (c.getVisibility() == GONE) {
                    continue;
                }
                if (x > 0 && x + c.getMeasuredWidth() > width) {
                    x = 0;
                    y += chipHeight + gap;
                }
                final int left = rtl ? width - x - c.getMeasuredWidth() : x;
                c.layout(left, y, left + c.getMeasuredWidth(), y + chipHeight);
                x += c.getMeasuredWidth() + gap;
            }
        }
    }
}
