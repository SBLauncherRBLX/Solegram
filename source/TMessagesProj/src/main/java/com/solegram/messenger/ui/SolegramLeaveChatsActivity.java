/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Leave every channel and group at once, except the ones the user ticks off. The whole chat list (main and archive)
 * is loaded first; every channel and group the account is in is listed, all ticked to leave except the ones the user
 * created (leaving those would give up their ownership). Leaving goes one chat at a time with a pause, the way
 * Telegram's own «Leave» does it, so the server does not slow the account down; it goes on if the screen is closed.
 */
public class SolegramLeaveChatsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_SELECT_ALL = 1;
    private static final int ID_SELECT_NONE = 2;
    private static final int ID_LEAVE = 3;
    private static final int ID_STOP = 4;
    private static final int ID_CHAT = 1000;

    /** Pause between two leaves: Telegram answers too many leaves in a row with a wait. */
    private static final long STEP_MS = 650;

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> own = new ArrayList<>();
    /** Chat ids that stay (unticked). */
    private final HashSet<Long> keep = new HashSet<>();
    private boolean loading = true;

    /** A leave in progress (one per account; it keeps going when the screen is closed). */
    private static Runner runner;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        loadMore();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        AndroidUtilities.cancelRunOnUIThread(loadTick);
        if (runner != null && runner.screen == this) {
            runner.screen = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_LeaveChatsTitle);
    }

    // region Loading the whole list

    private final Runnable loadTick = this::loadMore;

    /** Asks for the next page of the main list and the archive until both have reached their end. */
    private void loadMore() {
        final MessagesController mc = getMessagesController();
        boolean done = true;
        for (int folder = 0; folder <= 1; folder++) {
            if (!mc.isDialogsEndReached(folder) || !mc.isServerDialogsEndReached(folder)) {
                done = false;
                if (!mc.isLoadingDialogs(folder)) {
                    mc.loadDialogs(folder, -1, 100, !mc.isDialogsEndReached(folder));
                }
            }
        }
        if (done) {
            loading = false;
            collect();
        } else {
            AndroidUtilities.runOnUIThread(loadTick, 350);
        }
        refresh();
    }

    private void collect() {
        channels.clear();
        groups.clear();
        own.clear();
        final MessagesController mc = getMessagesController();
        final HashSet<Long> seen = new HashSet<>();
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (!DialogObject.isChatDialog(d.id) || !seen.add(d.id)) {
                continue;
            }
            final TLRPC.Chat chat = mc.getChat(-d.id);
            if (chat == null || ChatObject.isNotInChat(chat) || chat.migrated_to != null || ChatObject.isMonoForum(chat)) {
                continue;
            }
            if (chat.creator) {
                own.add(chat);
                keep.add(chat.id);
            } else if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                channels.add(chat);
            } else {
                groups.add(chat);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload && loading) {
            AndroidUtilities.cancelRunOnUIThread(loadTick);
            AndroidUtilities.runOnUIThread(loadTick, 50);
        }
    }
    // endregion

    private void refresh() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private int toLeave() {
        int n = 0;
        for (ArrayList<TLRPC.Chat> list : lists()) {
            for (TLRPC.Chat c : list) {
                if (!keep.contains(c.id)) n++;
            }
        }
        return n;
    }

    private ArrayList<TLRPC.Chat>[] lists() {
        @SuppressWarnings("unchecked")
        final ArrayList<TLRPC.Chat>[] l = new ArrayList[]{channels, groups, own};
        return l;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (loading) {
            items.add(UItem.asShadow(getString(R.string.SG_LeaveChatsLoading)));
            return;
        }
        final boolean busy = runner != null && runner.account == currentAccount && !runner.finished;
        if (busy) {
            runner.screen = this;
            items.add(UItem.asShadow(formatString(R.string.SG_LeaveChatsProgress, runner.done, runner.ids.size())));
            items.add(UItem.asButton(ID_STOP, getString(R.string.SG_LeaveChatsStop)).red());
            return;
        }
        if (channels.isEmpty() && groups.isEmpty() && own.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.SG_LeaveChatsNone)));
            return;
        }
        items.add(UItem.asShadow(getString(R.string.SG_LeaveChatsInfo)));
        items.add(UItem.asButton(ID_SELECT_ALL, getString(R.string.SG_LeaveChatsSelectAll)));
        items.add(UItem.asButton(ID_SELECT_NONE, getString(R.string.SG_LeaveChatsSelectNone)));
        items.add(UItem.asShadow(null));
        section(items, R.string.SG_LeaveChatsChannels, channels, 0);
        section(items, R.string.SG_LeaveChatsGroups, groups, channels.size());
        section(items, R.string.SG_LeaveChatsOwn, own, channels.size() + groups.size());
        if (!own.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.SG_LeaveChatsOwnInfo)));
        }
        final int n = toLeave();
        items.add(UItem.asButton(ID_LEAVE, n > 0 ? formatString(R.string.SG_LeaveChatsButton, n) : getString(R.string.SG_LeaveChatsNothing)).red());
        items.add(UItem.asShadow(null));
    }

    private void section(ArrayList<UItem> items, int title, ArrayList<TLRPC.Chat> list, int base) {
        if (list.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(getString(title) + " · " + list.size()));
        for (int i = 0; i < list.size(); i++) {
            final TLRPC.Chat chat = list.get(i);
            items.add(UItem.asUserCheckbox(ID_CHAT + base + i, chat).pad().setChecked(!keep.contains(chat.id)));
        }
    }

    private TLRPC.Chat chatAt(int id) {
        int i = id - ID_CHAT;
        for (ArrayList<TLRPC.Chat> list : lists()) {
            if (i < list.size()) {
                return list.get(i);
            }
            i -= list.size();
        }
        return null;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_CHAT) {
            final TLRPC.Chat chat = chatAt(item.id);
            if (chat != null) {
                if (!keep.remove(chat.id)) {
                    keep.add(chat.id);
                }
                refresh();
            }
            return;
        }
        switch (item.id) {
            case ID_SELECT_ALL:
                keep.clear();
                for (TLRPC.Chat c : own) keep.add(c.id); // the user's own chats are ticked one by one only
                refresh();
                break;
            case ID_SELECT_NONE:
                for (ArrayList<TLRPC.Chat> list : lists()) {
                    for (TLRPC.Chat c : list) keep.add(c.id);
                }
                refresh();
                break;
            case ID_LEAVE:
                confirm();
                break;
            case ID_STOP:
                if (runner != null) {
                    runner.stop();
                }
                refresh();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void confirm() {
        final ArrayList<Long> ids = new ArrayList<>();
        for (ArrayList<TLRPC.Chat> list : lists()) {
            for (TLRPC.Chat c : list) {
                if (!keep.contains(c.id)) ids.add(c.id);
            }
        }
        if (ids.isEmpty() || getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_LeaveChatsConfirmTitle))
            .setMessage(LocaleController.formatPluralString("SG_LeaveChatsConfirm", ids.size()))
            .setPositiveButton(getString(R.string.SG_LeaveChatsConfirmButton), (d, w) -> start(ids))
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_text_RedBold));
        }
    }

    private void start(ArrayList<Long> ids) {
        if (runner != null && !runner.finished) {
            return;
        }
        runner = new Runner(currentAccount, ids);
        runner.screen = this;
        runner.step();
        refresh();
    }

    private void onRunnerFinished(Runner r) {
        if (getParentActivity() == null) {
            return;
        }
        loading = true;
        keep.clear();
        loadMore();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, formatString(R.string.SG_LeaveChatsDone, r.done)).show();
    }

    /** Leaves the chats one by one; it holds no screen, only tells the open one how far it got. */
    private static final class Runner {
        final int account;
        final ArrayList<Long> ids;
        int done;
        boolean finished;
        SolegramLeaveChatsActivity screen;

        Runner(int account, ArrayList<Long> ids) {
            this.account = account;
            this.ids = ids;
        }

        void stop() {
            finished = true;
            final SolegramLeaveChatsActivity s = screen;
            if (s != null) {
                s.onRunnerFinished(this);
            }
        }

        void step() {
            if (finished) {
                return;
            }
            if (done >= ids.size()) {
                stop();
                return;
            }
            final long chatId = ids.get(done);
            try {
                final MessagesController mc = MessagesController.getInstance(account);
                final TLRPC.Chat chat = mc.getChat(chatId);
                final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
                // never the user's own chat by accident: leaving keeps it (no deleting), but ticking one is a choice
                if (chat != null && self != null && !ChatObject.isNotInChat(chat)) {
                    mc.deleteParticipantFromChat(chatId, self);
                }
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }
            done++;
            final SolegramLeaveChatsActivity s = screen;
            if (s != null) {
                s.refresh();
            }
            AndroidUtilities.runOnUIThread(this::step, STEP_MS);
        }
    }
}
