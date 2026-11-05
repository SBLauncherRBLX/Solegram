/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramTabs;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * The bottom bar's editor: a preview of the bar, the tabs in it (drag to reorder, tap to take out) and the ones
 * that are not (tap to add). Up to four, Chats always first; the bar keeps its size.
 */
public class SolegramTabsSheet extends BottomSheet {

    private final Theme.ResourcesProvider rp;
    private final Runnable onSaved;
    private final ArrayList<String> inBar = new ArrayList<>();
    private final LinearLayout preview;
    private final RecyclerView list;
    private final Adapter adapter;
    private final TextView hint;

    public static void show(BaseFragment fragment, Runnable onSaved) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new SolegramTabsSheet(fragment.getParentActivity(), fragment.getResourceProvider(), onSaved));
    }

    private SolegramTabsSheet(Context context, Theme.ResourcesProvider rp, Runnable onSaved) {
        super(context, false, rp);
        this.rp = rp;
        this.onSaved = onSaved;
        fixNavigationBar();
        SolegramMotion.springSheet(this);
        inBar.addAll(SolegramTabs.current());

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(12));

        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_TabsLayout));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(color(Theme.key_dialogTextBlack));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 2));
        hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTextColor(color(Theme.key_dialogTextGray3));
        hint.setText(formatString(R.string.SG_TabsHint, SolegramTabs.MAX));
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 12));

        // the bar as it will look: a capsule with the tabs, the first one selected
        preview = new LinearLayout(context);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(6), dp(6), dp(6), dp(6));
        final GradientDrawable capsule = new GradientDrawable();
        capsule.setCornerRadius(dp(30));
        capsule.setColor(Theme.multAlpha(color(Theme.key_dialogTextBlack), 0.06f));
        preview.setBackground(capsule);
        column.addView(preview, LayoutHelper.createLinear(300, 60, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setNestedScrollingEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        final ItemTouchHelper dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                final int p = h.getAdapterPosition();
                return p > 0 && p < inBar.size() ? super.getMovementFlags(rv, h) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                final int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a <= 0 || b <= 0 || a >= inBar.size() || b >= inBar.size()) {
                    return false;
                }
                inBar.add(b, inBar.remove(a));
                adapter.notifyItemMoved(a, b);
                SolegramMotion.haptic(rv);
                updatePreview();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder h, int state) {
                super.onSelectedChanged(h, state);
                if (h != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    h.itemView.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(6)).setDuration(160).start();
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                h.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(200).start();
                adapter.notifyDataSetChanged();
            }
        });
        dragger.attachToRecyclerView(list);
        adapter.dragger = dragger;
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(dp(14), dp(12), dp(14), 0);
        final TextView reset = button(context, R.string.SG_TabsReset, false);
        reset.setOnClickListener(v -> {
            SolegramTabs.reset();
            dismiss();
            if (onSaved != null) {
                onSaved.run();
            }
        });
        final TextView save = button(context, R.string.Save, true);
        save.setOnClickListener(v -> {
            SolegramTabs.save(inBar);
            dismiss();
            if (onSaved != null) {
                onSaved.run();
            }
        });
        buttons.addView(reset, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 6, 0));
        buttons.addView(save, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 0, 0));
        column.addView(buttons);

        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(column);
        setCustomView(scroll);
        updatePreview();
    }

    private int color(int key) {
        return Theme.getColor(key, rp);
    }

    private TextView button(Context context, int text, boolean primary) {
        final TextView b = new TextView(context);
        b.setText(getString(text));
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        final int accent = color(Theme.key_featuredStickers_addButton);
        b.setTextColor(primary ? Color.WHITE : accent);
        b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), primary ? accent : Theme.multAlpha(accent, 0.12f),
            primary ? color(Theme.key_featuredStickers_addButtonPressed) : Theme.multAlpha(accent, 0.2f)));
        return b;
    }

    private void updatePreview() {
        preview.removeAllViews();
        final int accent = color(Theme.key_featuredStickers_addButton);
        final int gray = color(Theme.key_dialogTextGray3);
        for (int i = 0; i < inBar.size(); i++) {
            final String kind = inBar.get(i);
            final LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            if (i == 0) {
                final GradientDrawable sel = new GradientDrawable();
                sel.setCornerRadius(dp(24));
                sel.setColor(Theme.multAlpha(accent, 0.14f));
                cell.setBackground(sel);
            }
            final ImageView icon = new ImageView(getContext());
            if (SolegramTabs.MUSIC.equals(kind)) {
                final SolegramMusicTabIcon d = new SolegramMusicTabIcon(22);
                icon.setImageDrawable(d);
            } else {
                icon.setImageResource(SolegramTabs.icon(kind));
            }
            icon.setColorFilter(new PorterDuffColorFilter(i == 0 ? accent : gray, PorterDuff.Mode.SRC_IN));
            cell.addView(icon, LayoutHelper.createLinear(22, 22));
            final TextView name = new TextView(getContext());
            name.setText(getString(SolegramTabs.name(kind)));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine();
            name.setTextColor(i == 0 ? accent : gray);
            cell.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            preview.addView(cell, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
    }

    private ArrayList<String> notInBar() {
        final ArrayList<String> out = new ArrayList<>();
        for (String k : SolegramTabs.ALL) {
            if (!inBar.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        ItemTouchHelper dragger;

        @Override
        public int getItemCount() {
            return inBar.size() + 1 + notInBar().size();
        }

        @Override
        public int getItemViewType(int position) {
            return position == inBar.size() ? 1 : 0;
        }

        @NonNull
        @Override
        @SuppressLint("ClickableViewAccessibility")
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            final Context context = parent.getContext();
            if (type == 1) {
                final TextView h = new TextView(context);
                h.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                h.setTypeface(AndroidUtilities.bold());
                h.setTextColor(color(Theme.key_dialogTextGray3));
                h.setPadding(dp(20), dp(14), dp(20), dp(6));
                h.setText(getString(R.string.SG_TabsNotInBar));
                h.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(h) {
                };
            }
            final LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(8), dp(10), dp(8));
            row.setBackgroundColor(color(Theme.key_dialogBackground));
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(icon, LayoutHelper.createLinear(30, 30, 0, 0, 14, 0));
            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(color(Theme.key_dialogTextBlack));
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final ImageView action = new ImageView(context);
            action.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(action, LayoutHelper.createLinear(40, 40));
            final ImageView handle = new ImageView(context);
            handle.setScaleType(ImageView.ScaleType.CENTER);
            handle.setImageResource(R.drawable.list_reorder);
            handle.setColorFilter(new PorterDuffColorFilter(color(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
            row.addView(handle, LayoutHelper.createLinear(40, 40));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(row) {
            };
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && dragger != null) {
                    dragger.startDrag(holder);
                }
                return false;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == 1) {
                return;
            }
            final boolean inside = position < inBar.size();
            final String kind = inside ? inBar.get(position) : notInBar().get(position - inBar.size() - 1);
            final LinearLayout row = (LinearLayout) holder.itemView;
            final ImageView icon = (ImageView) row.getChildAt(0);
            final TextView name = (TextView) row.getChildAt(1);
            final ImageView action = (ImageView) row.getChildAt(2);
            final View handle = row.getChildAt(3);
            final int accent = color(Theme.key_windowBackgroundWhiteBlueHeader);
            if (SolegramTabs.MUSIC.equals(kind)) {
                icon.setImageDrawable(new SolegramMusicTabIcon(24));
            } else {
                icon.setImageResource(SolegramTabs.icon(kind));
            }
            icon.setColorFilter(new PorterDuffColorFilter(inside ? accent : color(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
            name.setText(getString(SolegramTabs.name(kind)));
            final boolean locked = SolegramTabs.CHATS.equals(kind);
            handle.setVisibility(inside && !locked ? View.VISIBLE : View.INVISIBLE);
            action.setVisibility(locked ? View.INVISIBLE : View.VISIBLE);
            action.setImageResource(inside ? R.drawable.msg_delete : R.drawable.msg_add);
            action.setColorFilter(new PorterDuffColorFilter(inside ? color(Theme.key_text_RedRegular) : accent, PorterDuff.Mode.SRC_IN));
            action.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            action.setOnClickListener(v -> {
                if (inside) {
                    if (inBar.size() <= SolegramTabs.MIN) {
                        AndroidUtilities.shakeViewSpring(hint, 3);
                        return;
                    }
                    inBar.remove(kind);
                } else {
                    if (inBar.size() >= SolegramTabs.MAX) {
                        AndroidUtilities.shakeViewSpring(hint, 3);
                        SolegramMotion.haptic(v);
                        return;
                    }
                    inBar.add(kind);
                }
                SolegramIconMotion.play(action, SolegramIconMotion.POP);
                notifyDataSetChanged();
                updatePreview();
            });
            row.setAlpha(inside ? 1f : 0.85f);
        }
    }
}
