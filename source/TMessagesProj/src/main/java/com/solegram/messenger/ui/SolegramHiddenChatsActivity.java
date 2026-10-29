/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramHiddenChats;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.TopicsFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The hidden chats, shown after the phone's lock was passed: the same rows as the chat list (avatar, last
 * message, unread counter), a tap opens the chat, holding one brings it back to the list. The screen is kept out
 * of screenshots and the recent apps while it is open, and it closes (with what was opened from it) as soon as
 * the app leaves the screen.
 */
public class SolegramHiddenChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_DIALOG = 0;
    private static final int VIEW_INFO = 1;

    // Apple HIG indigo (light, dark), the tile of hidden chats in the settings too
    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};

    private RecyclerListView listView;
    private ListAdapter adapter;
    private View emptyView;
    private FlagSecureReason secure;
    private boolean locked, selfChange;

    /** The hidden chats in the order of the chat list: newest first (pinned ones are not on top here). */
    private final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();
    /** Hidden chats this device has not loaded yet (older than the loaded part of the list): asked for once. */
    private final ArrayList<Long> requested = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.encryptedChatUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramSettingsChanged);
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.encryptedChatUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramSettingsChanged);
        if (secure != null) {
            secure.detach();
            secure = null;
        }
        SolegramHiddenChats.endVisit(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_HiddenChats));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        fragmentView = frame;

        listView = new RecyclerListView(context, getResourceProvider());
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(SolegramMotion.itemAnimator());
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < dialogs.size()) {
                openChat(dialogs.get(position).id);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= dialogs.size()) {
                return false;
            }
            showOptions(view, dialogs.get(position).id);
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = createEmptyView(context);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));
        emptyView.setVisibility(dialogs.isEmpty() ? View.VISIBLE : View.GONE);

        // no screenshots, no picture in the recent apps, for as long as the hidden chats are open
        if (secure == null && getParentActivity() != null) {
            secure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
            secure.attach();
        }
        SolegramHiddenChats.startVisit(this);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // region Data

    private void load() {
        dialogs.clear();
        final long[] ids = SolegramHiddenChats.idsFor(currentAccount);
        if (ids == null) {
            return;
        }
        final MessagesController controller = getMessagesController();
        for (long id : ids) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(id);
            if (dialog == null) {
                // not loaded yet: a row with the name now, the last message once the server answered
                dialog = new TLRPC.TL_dialog();
                dialog.id = id;
                if (!requested.contains(id)) {
                    requested.add(id);
                    controller.solegramLoadDialog(id);
                }
            }
            dialogs.add(dialog);
        }
        Collections.sort(dialogs, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
    }

    private boolean sameOrder(List<TLRPC.Dialog> before) {
        if (before.size() != dialogs.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).id != dialogs.get(i).id || before.get(i) != dialogs.get(i)) {
                return false;
            }
        }
        return true;
    }

    private void reload() {
        final ArrayList<TLRPC.Dialog> before = new ArrayList<>(dialogs);
        load();
        if (adapter == null) {
            return;
        }
        if (sameOrder(before)) {
            updateRows(0);
        } else {
            adapter.notifyDataSetChanged();
        }
        SolegramMotion.setVisible(emptyView, dialogs.isEmpty(), true);
    }

    private void updateRows(int mask) {
        if (listView == null) {
            return;
        }
        for (int i = 0, n = listView.getChildCount(); i < n; i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof DialogCell) {
                ((DialogCell) child).update(mask);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (locked || selfChange) {
            return;
        }
        if (id == NotificationCenter.dialogsNeedReload || id == NotificationCenter.encryptedChatUpdated) {
            reload();
        } else if (id == NotificationCenter.updateInterfaces) {
            updateRows((Integer) args[0]);
        } else if (id == NotificationCenter.notificationsSettingsUpdated) {
            updateRows(0);
        } else if (id == NotificationCenter.solegramSettingsChanged && args.length > 0 && "hiddenChats".equals(args[0])) {
            reload();
        }
    }
    // endregion

    // region Actions

    private void openChat(long dialogId) {
        final Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (!getMessagesController().checkCanOpenChat(args, this)) {
            return;
        }
        final TLRPC.Chat chat = dialogId < 0 ? getMessagesController().getChat(-dialogId) : null;
        // forums open as from the chat list: the topics, or the chat with its topic tabs
        if (chat != null && chat.forum && !chat.monoforum) {
            if (ChatObject.areTabsEnabled(chat)) {
                final ChatActivity activity = new ChatActivity(args);
                ForumUtilities.applyTopic(activity, MessagesStorage.TopicKey.of(-chat.id, getMessagesController().getForumLastTopicId(chat.id)));
                presentFragment(activity);
            } else {
                presentFragment(new TopicsFragment(args));
            }
            return;
        }
        if (chat != null && chat.monoforum) {
            args.putInt("chatMode", ChatActivity.MODE_SUGGESTIONS);
            args.putBoolean("isSubscriberSuggestions", !ChatObject.canManageMonoForum(currentAccount, chat));
        }
        presentFragment(new ChatActivity(args));
    }

    private void showOptions(View row, long dialogId) {
        final boolean muted = getMessagesController().isDialogMuted(dialogId, 0);
        ItemOptions.makeOptions(this, row)
            .add(R.drawable.msg_archive_show, getString(R.string.SG_UnhideChat), () -> unhide(dialogId))
            .add(muted ? R.drawable.msg_unmute : R.drawable.msg_mute, getString(muted ? R.string.Unmute : R.string.Mute), () -> {
                getNotificationsController().setDialogNotificationsSettings(dialogId, 0,
                    muted ? NotificationsController.SETTING_MUTE_UNMUTE : NotificationsController.SETTING_MUTE_FOREVER);
                BulletinFactory.createMuteBulletin(this, !muted, null).show();
            })
            .setGravity(Gravity.RIGHT)
            .show();
    }

    private void unhide(long dialogId) {
        final int index = indexOf(dialogId);
        // the list follows here, row by row, instead of being rebuilt by the change's own notifications
        selfChange = true;
        try {
            SolegramHiddenChats.unhide(currentAccount, dialogId);
        } finally {
            selfChange = false;
        }
        if (index >= 0 && index < dialogs.size()) {
            dialogs.remove(index);
            if (adapter != null) {
                if (dialogs.isEmpty()) {
                    adapter.notifyItemRangeRemoved(0, 2); // the row and the note under the list
                } else {
                    adapter.notifyItemRemoved(index);
                    if (index == dialogs.size()) {
                        adapter.notifyItemChanged(index - 1); // the new last row loses its separator
                    }
                }
            }
        }
        SolegramMotion.setVisible(emptyView, dialogs.isEmpty(), true);
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_unhide, getString(R.string.SG_HiddenChatShown),
            getString(R.string.Undo), () -> {
                final ArrayList<Long> list = new ArrayList<>();
                list.add(dialogId);
                SolegramHiddenChats.hide(currentAccount, list);
            }).show();
    }

    private int indexOf(long dialogId) {
        for (int i = 0; i < dialogs.size(); i++) {
            if (dialogs.get(i).id == dialogId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The app left the screen: this screen and everything opened from it close at once, without animation,
     * so the next look at the app shows the chat list and the hidden chats ask for the lock again.
     */
    public void lock() {
        if (locked) {
            return;
        }
        locked = true;
        try {
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                PhotoViewer.getInstance().closePhoto(false, true);
            }
        } catch (Throwable ignore) {
        }
        final INavigationLayout layout = getParentLayout();
        if (layout == null) {
            return;
        }
        final List<BaseFragment> stack = layout.getFragmentStack();
        final int index = stack.indexOf(this);
        if (index < 0) {
            return;
        }
        final ArrayList<BaseFragment> opened = new ArrayList<>(stack.subList(index, stack.size()));
        final BaseFragment top = opened.remove(opened.size() - 1);
        for (BaseFragment fragment : opened) {
            layout.removeFragmentFromStack(fragment, true);
        }
        top.finishFragment(false);
    }
    // endregion

    // region Views

    private View createEmptyView(Context context) {
        final FrameLayout frame = new FrameLayout(context);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        column.addView(new TileView(context), LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        title.setText(getString(R.string.SG_HiddenEmpty));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));

        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(dp(2), 1f);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, getResourceProvider()));
        text.setText(getString(R.string.SG_HiddenEmptyInfo));
        column.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 40, 8, 40, 0));

        frame.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 48));
        return frame;
    }

    /** The rounded indigo tile with the crossed eye, as the hidden chats row of the settings shows it. */
    private static final class TileView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Drawable icon;
        private int shaderHeight;

        TileView(Context context) {
            super(context);
            icon = ContextCompat.getDrawable(context, R.drawable.msg_archive_hide).mutate();
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int h = getHeight();
            if (shaderHeight != h) {
                shaderHeight = h;
                final int base = Theme.isCurrentThemeDark() ? TILE_INDIGO[1] : TILE_INDIGO[0];
                paint.setShader(new LinearGradient(0, 0, 0, h, androidx.core.graphics.ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base, Shader.TileMode.CLAMP));
            }
            rect.set(0, 0, getWidth(), h);
            // iOS app icon roundness: about 22.5% of the side
            canvas.drawRoundRect(rect, h * 0.225f, h * 0.225f, paint);
            final int size = dp(44);
            final int left = (getWidth() - size) / 2, top = (h - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_DIALOG;
        }

        @Override
        public int getItemCount() {
            return dialogs.isEmpty() ? 0 : dialogs.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position < dialogs.size() ? VIEW_DIALOG : VIEW_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == VIEW_DIALOG) {
                view = new DialogCell(null, context, false, false, currentAccount, getResourceProvider());
            } else {
                final TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context, getResourceProvider());
                cell.setText(getString(R.string.SG_HiddenListInfo));
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != VIEW_DIALOG) {
                return;
            }
            final DialogCell cell = (DialogCell) holder.itemView;
            final TLRPC.Dialog dialog = dialogs.get(position);
            cell.useSeparator = position != dialogs.size() - 1;
            cell.fullSeparator = false;
            if (getMessagesController().dialogs_dict.get(dialog.id) != null) {
                cell.setDialog(dialog, DialogsActivity.DIALOGS_TYPE_DEFAULT, 0);
            } else {
                cell.setDialog(dialog.id, null, 0, false, false);
            }
        }
    }
    // endregion
}
