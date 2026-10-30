/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramSavedTags;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Tags of Saved Messages kept on this device: the tags with how many messages carry them, and the messages with
 * the chosen tag (all tagged ones at first); a message opens in Saved Messages.
 */
public class SolegramSavedTagsActivity extends UniversalFragment {

    private static final int ID_ALL = 1;
    private static final int ID_TAG_BASE = 1000;
    private static final int ID_MESSAGE_BASE = 100000;

    private String selected;
    private ArrayList<String[]> tags = new ArrayList<>();
    private ArrayList<SolegramSavedTags.Entry> messages = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.SG_SavedTags);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        tags = SolegramSavedTags.tags(currentAccount);
        if (selected != null) {
            boolean still = false;
            for (String[] t : tags) {
                still |= t[0].equals(selected);
            }
            if (!still) {
                selected = null;
            }
        }
        if (tags.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.SG_SavedTagsEmpty)));
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.SG_SavedTagsList)));
        items.add(UItem.asButton(ID_ALL, LocaleController.getString(R.string.SG_SavedTagsAll), selected == null ? "✓" : ""));
        for (int i = 0; i < tags.size(); i++) {
            final String[] t = tags.get(i);
            items.add(UItem.asButton(ID_TAG_BASE + i, t[0] + "   " + LocaleController.formatString(R.string.SG_SavedTagsCount, Integer.parseInt(t[1])),
                t[0].equals(selected) ? "✓" : ""));
        }
        items.add(UItem.asShadow(null));

        messages = SolegramSavedTags.messages(currentAccount, selected);
        items.add(UItem.asHeader(selected == null ? LocaleController.getString(R.string.SG_SavedTagsMessages) : selected + "  " + LocaleController.getString(R.string.SG_SavedTagsMessages)));
        for (int i = 0; i < messages.size(); i++) {
            final SolegramSavedTags.Entry e = messages.get(i);
            final String text = TextUtils.isEmpty(e.preview) ? LocaleController.getString(R.string.SG_SavedTagsMedia) : e.preview;
            items.add(UItem.asButton(ID_MESSAGE_BASE + i, TextUtils.join("", e.tags) + "  " + text,
                e.date > 0 ? LocaleController.formatDateAudio(e.date, false) : ""));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.SG_SavedTagsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ALL) {
            selected = null;
            listView.adapter.update(true);
        } else if (item.id >= ID_TAG_BASE && item.id < ID_TAG_BASE + tags.size()) {
            final String tag = tags.get(item.id - ID_TAG_BASE)[0];
            selected = tag.equals(selected) ? null : tag;
            listView.adapter.update(true);
        } else if (item.id >= ID_MESSAGE_BASE && item.id < ID_MESSAGE_BASE + messages.size()) {
            final Bundle args = new Bundle();
            args.putLong("user_id", getUserConfig().getClientUserId());
            args.putInt("message_id", messages.get(item.id - ID_MESSAGE_BASE).messageId);
            presentFragment(new ChatActivity(args));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** The tag picker for a saved message: the emoji on it are marked, a tap puts a tag on or takes it off. */
    public static void showPicker(BaseFragment fragment, MessageObject message) {
        final Context context = fragment.getParentActivity();
        if (context == null || message == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final ArrayList<String> current = SolegramSavedTags.tagsOf(account, message.getId());
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_SavedTag));
        final GridLayout grid = new GridLayout(context);
        grid.setColumnCount(6);
        grid.setPadding(dp(16), dp(8), dp(16), dp(4));
        final AlertDialog[] dialog = new AlertDialog[1];
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider());
        for (String emoji : SolegramSavedTags.EMOJI) {
            final TextView cell = new TextView(context);
            cell.setText(emoji);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            cell.setGravity(Gravity.CENTER);
            final boolean on = current.contains(emoji);
            cell.setBackground(on ? Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(accent, 0.22f))
                : Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            cell.setOnClickListener(v -> {
                SolegramSavedTags.toggle(account, message, emoji);
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                    LocaleController.formatString(on ? R.string.SG_SavedTagRemoved : R.string.SG_SavedTagAdded, emoji)).show();
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
            });
            final GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(46);
            lp.height = dp(46);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(cell, lp);
        }
        builder.setView(grid);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.SG_SavedTags), (d, w) -> fragment.presentFragment(new SolegramSavedTagsActivity()));
        dialog[0] = builder.create();
        fragment.showDialog(dialog[0]);
    }
}
