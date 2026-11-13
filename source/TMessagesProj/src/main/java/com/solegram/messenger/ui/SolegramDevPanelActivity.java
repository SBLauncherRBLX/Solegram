/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import com.solegram.messenger.Solegram;
import com.solegram.messenger.SolegramBadges;
import com.solegram.messenger.updater.UpdateController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The developer's panel: only for an account that may change the official channel's info (checked with Telegram
 * when the panel opens, and Telegram refuses every change from anyone else anyway). Gives and takes Solegram badges
 * (the list lives in the channel's description), and holds a few tools for testing.
 */
public class SolegramDevPanelActivity extends UniversalFragment {

    private static final int ID_STATUS = 1;
    private static final int ID_SYNC = 2;
    private static final int ID_MY_BADGE = 3;
    private static final int ID_ADD_TESTER = 4;
    private static final int ID_TESTER_BASE = 1000;
    private static final int ID_PREVIEW_DEV = 10;
    private static final int ID_PREVIEW_TESTER = 11;
    private static final int ID_PREVIEW_OFFICIAL = 12;
    private static final int ID_CHECK_UPDATE = 20;
    private static final int ID_COPY_ID = 21;
    private static final int ID_OPEN_CHANNEL = 22;
    private static final int ID_BLOCK_PACK = 30;
    private static final int ID_BLOCK_STATUS = 31;
    private static final int ID_BLOCKED_BASE = 5000;

    /** null while the rights are being checked, then whether this account may manage the list. */
    private Boolean allowed;
    private boolean creator;
    private String visibleAbout = "";
    private boolean busy;
    private final ArrayList<long[]> testers = new ArrayList<>();
    private final ArrayList<long[]> blocked = new ArrayList<>();

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_DevPanel);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        listView.setSections();
        check();
        return view;
    }

    /** Asks Telegram again whether this account may change the official channel's info, and reads the list. */
    private void check() {
        SolegramBadges.refresh((ok, error) -> {
            SolegramDevPanelActivity.this.resolveRights();
        });
    }

    private void resolveRights() {
        // the same check as for every change: the official channel, by its pinned id, and this account's rights in it
        SolegramBadges.resolve(currentAccount, (chat, error) -> {
            allowed = SolegramBadges.canManage(chat);
            creator = chat != null && chat.creator;
            if (!allowed) {
                // not for this account: nothing of the panel stays on the screen
                BulletinFactory.global().createErrorBulletin(getString(R.string.SG_DevPanelDenied)).show();
                finishFragment();
                return;
            }
            final TLRPC.TL_channels_getFullChannel full = new TLRPC.TL_channels_getFullChannel();
            full.channel = MessagesController.getInputChannel(chat);
            getConnectionsManager().sendRequest(full, (res2, err2) -> AndroidUtilities.runOnUIThread(() -> {
                if (res2 instanceof TLRPC.TL_messages_chatFull && ((TLRPC.TL_messages_chatFull) res2).full_chat != null) {
                    visibleAbout = SolegramBadges.strip(((TLRPC.TL_messages_chatFull) res2).full_chat.about);
                }
                update();
            }));
            update();
        });
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (allowed == null || !allowed) {
            items.add(UItem.asShadow(getString(R.string.SG_DevPanelChecking)));
            return;
        }
        final HashMap<Long, Integer> list = SolegramBadges.copy();
        testers.clear();
        blocked.clear();
        int developers = 0;
        for (long[] e : SolegramBadges.entries()) {
            if (e[1] == SolegramBadges.BLOCK_SET || e[1] == SolegramBadges.BLOCK_EMOJI) {
                blocked.add(e);
            } else if (e[1] == SolegramBadges.TESTER) {
                testers.add(e);
            } else if (e[1] == SolegramBadges.DEVELOPER) {
                developers++;
            }
        }

        items.add(UItem.asHeader(getString(R.string.SG_DevPanelChannel)));
        items.add(badgeRow(ID_STATUS, SolegramBadges.OFFICIAL, 24, "@" + SolegramBadges.officialUsername(),
            getString(creator ? R.string.SG_DevPanelCreator : R.string.SG_DevPanelAdmin)));
        items.add(UItem.asButton(ID_SYNC, R.drawable.msg_retry, getString(R.string.SG_DevPanelSync),
            formatString(R.string.SG_DevPanelRoom, Math.max(0, SolegramBadges.roomLeft(visibleAbout, list)))));
        items.add(UItem.asShadow(getString(R.string.SG_DevPanelChannelInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_BadgeDeveloper)));
        final boolean mine = list.get(getUserConfig().getClientUserId()) != null && list.get(getUserConfig().getClientUserId()) == SolegramBadges.DEVELOPER;
        items.add(UItem.asCheck(ID_MY_BADGE, getString(R.string.SG_DevPanelMyBadge)).setChecked(mine).setEnabled(creator && !busy));
        items.add(UItem.asShadow(getString(creator ? R.string.SG_DevPanelMyBadgeInfo : R.string.SG_DevPanelMyBadgeCreatorOnly)));

        items.add(UItem.asHeader(formatString(R.string.SG_DevPanelTesters, testers.size())));
        for (int i = 0; i < testers.size(); i++) {
            final long id = testers.get(i)[0];
            final TLRPC.User user = getMessagesController().getUser(id);
            final String name = user != null ? UserObject.getUserName(user) : getString(R.string.SG_DevPanelUnknownUser);
            final String handle = user != null && !TextUtils.isEmpty(UserObject.getPublicUsername(user)) ? "@" + UserObject.getPublicUsername(user) : String.valueOf(id);
            items.add(badgeRow(ID_TESTER_BASE + i, SolegramBadges.TESTER, 22, name, handle));
        }
        items.add(UItem.asButton(ID_ADD_TESTER, R.drawable.msg_add, getString(R.string.SG_DevPanelAddTester)).accent().setEnabled(!busy));
        items.add(UItem.asShadow(getString(R.string.SG_DevPanelTestersInfo)));

        // imitations: lookalike custom emoji (a whole pack, or the one someone wears) are hidden in every Solegram
        items.add(UItem.asHeader(getString(R.string.SG_DevPanelFakes)));
        for (int i = 0; i < blocked.size(); i++) {
            final long[] e = blocked.get(i);
            String title = null;
            if (e[1] == SolegramBadges.BLOCK_SET) {
                final TLRPC.TL_messages_stickerSet set = getMediaDataController().getStickerSetById(e[0]);
                title = set != null && set.set != null ? set.set.title : null;
            }
            items.add(UItem.asButton(ID_BLOCKED_BASE + i, R.drawable.msg_block,
                title != null ? title : getString(e[1] == SolegramBadges.BLOCK_SET ? R.string.SG_DevPanelBlockedPack : R.string.SG_DevPanelBlockedEmoji),
                String.valueOf(e[0])));
        }
        items.add(UItem.asButton(ID_BLOCK_PACK, R.drawable.msg_block, getString(R.string.SG_DevPanelBlockPack)).setEnabled(!busy));
        items.add(UItem.asButton(ID_BLOCK_STATUS, R.drawable.msg_block, getString(R.string.SG_DevPanelBlockStatus)).setEnabled(!busy));
        items.add(UItem.asShadow(getString(R.string.SG_DevPanelFakesInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_DevPanelPreview)));
        items.add(badgeRow(ID_PREVIEW_DEV, SolegramBadges.DEVELOPER, 24, getString(R.string.SG_BadgeDeveloper), developers > 0 ? String.valueOf(developers) : ""));
        items.add(badgeRow(ID_PREVIEW_TESTER, SolegramBadges.TESTER, 24, getString(R.string.SG_BadgeTester), String.valueOf(testers.size())));
        items.add(UItem.asButton(ID_PREVIEW_OFFICIAL, new SolegramBadgeDrawable(SolegramBadges.OFFICIAL, dp(24)), getString(R.string.SG_BadgeOfficial)));
        items.add(UItem.asShadow(getString(R.string.SG_DevPanelPreviewInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_DevPanelTools)));
        items.add(UItem.asButton(ID_CHECK_UPDATE, R.drawable.msg_download, getString(R.string.SG_DevPanelCheckUpdate), Solegram.getVersionName()));
        items.add(UItem.asButton(ID_COPY_ID, R.drawable.msg_copy, getString(R.string.SG_DevPanelCopyId), String.valueOf(getUserConfig().getClientUserId())));
        items.add(UItem.asButton(ID_OPEN_CHANNEL, R.drawable.msg_channel, getString(R.string.SG_DevPanelOpenChannel)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (allowed == null || !allowed) {
            return;
        }
        switch (item.id) {
            case ID_SYNC:
                check();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_DevPanelSynced)).show();
                return;
            case ID_MY_BADGE: {
                if (!creator || busy) {
                    return;
                }
                final HashMap<Long, Integer> list = SolegramBadges.copy();
                final long me = getUserConfig().getClientUserId();
                if (list.get(me) != null && list.get(me) == SolegramBadges.DEVELOPER) {
                    list.remove(me);
                } else {
                    list.put(me, SolegramBadges.DEVELOPER);
                }
                save(list);
                return;
            }
            case ID_ADD_TESTER:
                if (!busy) {
                    askTester();
                }
                return;
            case ID_BLOCK_PACK:
                if (!busy) {
                    ask(R.string.SG_DevPanelBlockPack, R.string.SG_DevPanelBlockPackAsk, R.string.SG_DevPanelBlockPackHint, this::blockPack);
                }
                return;
            case ID_BLOCK_STATUS:
                if (!busy) {
                    ask(R.string.SG_DevPanelBlockStatus, R.string.SG_DevPanelBlockStatusAsk, R.string.SG_DevPanelTesterHint, this::blockStatus);
                }
                return;
            case ID_PREVIEW_DEV:
                SolegramBadgeSheet.show(this, SolegramBadges.DEVELOPER);
                return;
            case ID_PREVIEW_TESTER:
                SolegramBadgeSheet.show(this, SolegramBadges.TESTER);
                return;
            case ID_PREVIEW_OFFICIAL:
            case ID_STATUS:
                SolegramBadgeSheet.show(this, SolegramBadges.OFFICIAL);
                return;
            case ID_CHECK_UPDATE:
                UpdateController.getInstance().checkNow(this, true);
                return;
            case ID_COPY_ID:
                AndroidUtilities.addToClipboard(String.valueOf(getUserConfig().getClientUserId()));
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.SG_DevPanelIdCopied)).show();
                return;
            case ID_OPEN_CHANNEL:
                getMessagesController().openByUserName(SolegramBadges.officialUsername(), this, 1);
                return;
        }
        if (item.id >= ID_BLOCKED_BASE && item.id < ID_BLOCKED_BASE + blocked.size()) {
            final long id = blocked.get(item.id - ID_BLOCKED_BASE)[0];
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.SG_DevPanelFakes))
                .setMessage(getString(R.string.SG_DevPanelUnblock))
                .setPositiveButton(getString(R.string.SG_DevPanelUnblockButton), (d, w) -> {
                    final HashMap<Long, Integer> list = SolegramBadges.copy();
                    list.remove(id);
                    save(list);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
            return;
        }
        if (item.id >= ID_TESTER_BASE && item.id < ID_TESTER_BASE + testers.size()) {
            final long id = testers.get(item.id - ID_TESTER_BASE)[0];
            final TLRPC.User user = getMessagesController().getUser(id);
            new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.SG_DevPanelTesterTitle))
                .setMessage(formatString(R.string.SG_DevPanelRemoveTester, user != null ? UserObject.getUserName(user) : String.valueOf(id)))
                .setPositiveButton(getString(R.string.SG_DevPanelRemove), (d, w) -> {
                    final HashMap<Long, Integer> list = SolegramBadges.copy();
                    list.remove(id);
                    save(list);
                })
                .setNeutralButton(getString(R.string.SG_DevPanelOpenProfile), (d, w) -> presentFragment(ProfileActivity.of(id)))
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
        }
    }

    /** A row with a badge in front and a value at the end. */
    private static UItem badgeRow(int id, int role, int sizeDp, CharSequence text, CharSequence value) {
        final UItem item = UItem.asButton(id, new SolegramBadgeDrawable(role, dp(sizeDp)), text);
        item.textValue = value;
        return item;
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Asks for an @username or a numeric id and gives that person the tester's badge. */
    private void askTester() {
        ask(R.string.SG_DevPanelAddTester, R.string.SG_DevPanelTesterAsk, R.string.SG_DevPanelTesterHint, this::findPerson);
    }

    private interface Answer {
        void run(String text);
    }

    /** A one-line question in a dialog. */
    private void ask(int title, int message, int hint, Answer answer) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setHint(getString(hint));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField, getResourceProvider()), Theme.getColor(Theme.key_dialogInputFieldActivated, getResourceProvider()), Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        final FrameLayout frame = new FrameLayout(context);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, 0, 24, 6, 24, 0));
        new AlertDialog.Builder(context, getResourceProvider())
            .setTitle(getString(title))
            .setMessage(getString(message))
            .setView(frame)
            .setPositiveButton(getString(R.string.OK), (d, w) -> answer.run(input.getText().toString().trim()))
            .setNegativeButton(getString(R.string.Cancel), null)
            .show();
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }

    private void findPerson(String query) {
        if (TextUtils.isEmpty(query)) {
            return;
        }
        String q = query;
        if (q.startsWith("https://t.me/")) {
            q = q.substring("https://t.me/".length());
        } else if (q.startsWith("t.me/")) {
            q = q.substring("t.me/".length());
        }
        if (q.startsWith("@")) {
            q = q.substring(1);
        }
        if (q.matches("\\d{1,15}")) {
            give(Long.parseLong(q));
            return;
        }
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = q;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer && ((TLRPC.TL_contacts_resolvedPeer) response).peer instanceof TLRPC.TL_peerUser) {
                final TLRPC.TL_contacts_resolvedPeer r = (TLRPC.TL_contacts_resolvedPeer) response;
                getMessagesController().putUsers(r.users, false);
                give(r.peer.user_id);
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelNotFound)).show();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** A link to an emoji pack (t.me/addemoji/NAME) or its short name: the whole pack is hidden in every Solegram. */
    private void blockPack(String query) {
        String q = query;
        final int slash = q.lastIndexOf('/');
        if (slash >= 0) {
            q = q.substring(slash + 1);
        }
        final int qm = q.indexOf('?');
        if (qm >= 0) {
            q = q.substring(0, qm);
        }
        if (TextUtils.isEmpty(q)) {
            return;
        }
        final TLRPC.TL_messages_getStickerSet req = new TLRPC.TL_messages_getStickerSet();
        final TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = q;
        req.stickerset = input;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_stickerSet && ((TLRPC.TL_messages_stickerSet) response).set != null) {
                final TLRPC.TL_messages_stickerSet set = (TLRPC.TL_messages_stickerSet) response;
                getMediaDataController().putStickerSet(set);
                final HashMap<Long, Integer> list = SolegramBadges.copy();
                list.put(set.set.id, SolegramBadges.BLOCK_SET);
                save(list);
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelPackNotFound)).show();
            }
        }));
    }

    /** The emoji status someone wears (found by @username or id) is hidden in every Solegram. */
    private void blockStatus(String query) {
        String q = query.startsWith("@") ? query.substring(1) : query;
        if (q.matches("\\d{1,15}")) {
            blockStatusOf(getMessagesController().getUser(Long.parseLong(q)));
            return;
        }
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = q;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                final TLRPC.TL_contacts_resolvedPeer r = (TLRPC.TL_contacts_resolvedPeer) response;
                getMessagesController().putUsers(r.users, false);
                getMessagesController().putChats(r.chats, false);
                if (r.peer instanceof TLRPC.TL_peerUser) {
                    blockStatusOf(getMessagesController().getUser(r.peer.user_id));
                } else if (r.peer instanceof TLRPC.TL_peerChannel) {
                    final TLRPC.Chat chat = getMessagesController().getChat(r.peer.channel_id);
                    blockDocument(chat != null && chat.emoji_status instanceof TLRPC.TL_emojiStatus ? ((TLRPC.TL_emojiStatus) chat.emoji_status).document_id : 0);
                }
            } else {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelNotFound)).show();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void blockStatusOf(TLRPC.User user) {
        blockDocument(user != null && user.emoji_status instanceof TLRPC.TL_emojiStatus ? ((TLRPC.TL_emojiStatus) user.emoji_status).document_id : 0);
    }

    private void blockDocument(long documentId) {
        if (documentId == 0) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelNoStatus)).show();
            return;
        }
        final HashMap<Long, Integer> list = SolegramBadges.copy();
        list.put(documentId, SolegramBadges.BLOCK_EMOJI);
        save(list);
    }

    private void give(long userId) {
        if (userId <= 0) {
            return;
        }
        final HashMap<Long, Integer> list = SolegramBadges.copy();
        final Integer had = list.get(userId);
        if (had != null && had == SolegramBadges.DEVELOPER) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelIsDeveloper)).show();
            return;
        }
        list.put(userId, SolegramBadges.TESTER);
        save(list);
    }

    /** Writes the list into the channel's description; the panel shows the result. */
    private void save(Map<Long, Integer> list) {
        busy = true;
        update();
        SolegramBadges.write(currentAccount, list, (ok, error) -> {
            busy = false;
            if (ok) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_DevPanelSaved)).show();
            } else if ("ABOUT_TOO_LONG".equals(error)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelFull)).show();
            } else if ("CHAT_ADMIN_REQUIRED".equals(error)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_DevPanelDenied)).show();
                finishFragment();
                return;
            } else {
                BulletinFactory.of(this).createErrorBulletin(formatString(R.string.SG_DevPanelError, error == null ? "?" : error)).show();
            }
            update();
        });
    }
}
