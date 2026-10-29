/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import com.solegram.messenger.plugins.PluginMenuItem;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Solegram and plugin entries in the message context menu, chat header menu and profile menu. */
public final class SolegramMessageMenu {

    public static final int OPTION_EDIT_HISTORY = 9000;
    public static final int OPTION_FORGET_DELETED = 9001;
    public static final int OPTION_MESSAGE_DETAILS = 9002;
    public static final int OPTION_TRANSLATE_HERE = 9003;
    public static final int OPTION_SUMMARY = 9004;
    public static final int OPTION_FILTER_SIMILAR = 9005;
    public static final int OPTION_REMIND = 9006;
    public static final int OPTION_SOLEDROP = 9007;
    public static final int OPTION_PLUGIN_BASE = 9100;
    public static final int HEADER_PLUGIN_BASE = 20000;
    public static final int PROFILE_PLUGIN_BASE = 21000;

    private static List<PluginMenuItem> lastMessageItems = new ArrayList<>();
    private static Map<String, Object> lastMessageContext;
    /** Plugin items shown in each open chat's header menu (several chats can be on the back stack). */
    private static final Map<ChatActivity, List<PluginMenuItem>> headerItems = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ProfileActivity, List<PluginMenuItem>> profileItems = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ProfileActivity, Map<String, Object>> profileContexts = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private SolegramMessageMenu() {
    }

    // region Context builders

    public static Map<String, Object> chatContext(ChatActivity chat) {
        Map<String, Object> ctx = SolegramUi.baseContext(chat);
        ctx.put("dialog_id", chat.getDialogId());
        TLRPC.User user = chat.getCurrentUser();
        TLRPC.Chat c = chat.getCurrentChat();
        if (user != null) {
            ctx.put("user", user);
            ctx.put("userId", user.id);
            ctx.put("userFull", chat.getCurrentUserInfo());
        }
        if (c != null) {
            ctx.put("chat", c);
            ctx.put("chatId", c.id);
            ctx.put("chatFull", chat.getCurrentChatInfo());
        }
        if (chat.getCurrentEncryptedChat() != null) {
            ctx.put("encryptedChat", chat.getCurrentEncryptedChat());
        }
        return ctx;
    }
    // endregion

    // region Message context menu

    public static void fill(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group,
                            ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (chat == null || message == null) {
            return;
        }
        int account = chat.getCurrentAccount();
        if (SolegramConfig.saveEditsHistory && MessageVault.hasEditHistory(account, message)) {
            items.add(LocaleController.getString(R.string.SG_EditHistory));
            options.add(OPTION_EDIT_HISTORY);
            icons.add(R.drawable.msg_recent);
        }
        if (MessageVault.isDeleted(account, message)) {
            items.add(LocaleController.getString(R.string.SG_DeleteKept));
            options.add(OPTION_FORGET_DELETED);
            icons.add(R.drawable.msg_delete);
        }
        final MessageObject translateTarget = SolegramInlineTranslate.target(message, group);
        if (SolegramConfig.translateHere && SolegramInlineTranslate.canTranslate(translateTarget)) {
            final boolean shownNow = SolegramInlineTranslate.isShown(translateTarget);
            items.add(LocaleController.getString(shownNow ? R.string.SG_ShowOriginal : R.string.SG_TranslateHere));
            options.add(OPTION_TRANSLATE_HERE);
            icons.add(R.drawable.msg_translate);
        }
        if (SolegramSummarizer.canSummarize(translateTarget)) {
            final boolean open = translateTarget.messageOwner.summarizedOpen;
            items.add(LocaleController.getString(open ? R.string.SG_SummaryShowFull : R.string.SG_Summarize));
            options.add(OPTION_SUMMARY);
            icons.add(R.drawable.sg_ai_summary);
        }
        // SoleDrop: right after Forward, for what may be forwarded (an album goes whole)
        final int forwardIndex = options.indexOf(ChatActivity.OPTION_FORWARD);
        if (forwardIndex >= 0 && com.solegram.messenger.soledrop.SoleDropItem.messages(soleDropMessages(message, group)) != null) {
            items.add(forwardIndex + 1, LocaleController.getString(R.string.SG_SoleDrop));
            options.add(forwardIndex + 1, OPTION_SOLEDROP);
            icons.add(forwardIndex + 1, R.drawable.msg_soledrop);
        }
        if (SolegramReminders.canRemindIn(chat) && SolegramReminders.canRemind(message)) {
            items.add(LocaleController.getString(R.string.SG_Remind));
            options.add(OPTION_REMIND);
            icons.add(R.drawable.msg_notifications);
        }
        // message filters: a rule made from this message (its words, sender or forward source)
        if (chat.getChatMode() == ChatActivity.MODE_DEFAULT && chat.getDialogId() != chat.getUserConfig().getClientUserId()
            && SolegramMessageFilters.canFilterMessage(translateTarget != null ? translateTarget : message)) {
            items.add(LocaleController.getString(R.string.SG_FilterSimilar));
            options.add(OPTION_FILTER_SIMILAR);
            icons.add(R.drawable.msg_archive_hide);
        }
        if (SolegramConfig.messageDetails) {
            items.add(LocaleController.getString(R.string.SG_MessageDetails));
            options.add(OPTION_MESSAGE_DETAILS);
            icons.add(R.drawable.msg_info);
        }
        lastMessageItems = new ArrayList<>();
        if (PluginsController.isEngineAvailable()) {
            Map<String, Object> ctx = chatContext(chat);
            ctx.put("message", message);
            if (group != null) {
                ctx.put("groupedMessages", group);
            }
            lastMessageContext = ctx;
            List<PluginMenuItem> pluginItems = PluginsController.getInstance().getMenuItems(PluginMenuItem.MESSAGE_CONTEXT_MENU, ctx);
            for (int i = 0; i < pluginItems.size(); i++) {
                PluginMenuItem item = pluginItems.get(i);
                items.add(item.text);
                options.add(OPTION_PLUGIN_BASE + i);
                int icon = item.getIconResId();
                icons.add(icon != 0 ? icon : R.drawable.msg_bots);
            }
            lastMessageItems = pluginItems;
        }
    }

    /** @return true when the option belongs to Solegram and was handled. */
    public static boolean process(ChatActivity chat, int option, MessageObject message, MessageObject.GroupedMessages group) {
        if (option == OPTION_EDIT_HISTORY) {
            showEditHistory(chat, message);
            return true;
        }
        if (option == OPTION_FORGET_DELETED) {
            forgetDeleted(chat, message, group);
            return true;
        }
        if (option == OPTION_MESSAGE_DETAILS) {
            showMessageDetails(chat, message);
            return true;
        }
        if (option == OPTION_SUMMARY) {
            chat.solegramToggleSummary(SolegramInlineTranslate.target(message, group));
            return true;
        }
        if (option == OPTION_SOLEDROP) {
            com.solegram.messenger.soledrop.SoleDrop.open(chat, com.solegram.messenger.soledrop.SoleDropItem.messages(soleDropMessages(message, group)));
            return true;
        }
        if (option == OPTION_REMIND) {
            // an album is one reminder with all of its parts
            com.solegram.messenger.ui.SolegramReminderSheet.show(chat, group != null && !group.messages.isEmpty() ? group.messages : java.util.Collections.singletonList(message));
            return true;
        }
        if (option == OPTION_FILTER_SIMILAR) {
            final MessageObject target = SolegramInlineTranslate.target(message, group);
            chat.presentFragment(com.solegram.messenger.ui.SolegramFilterEditActivity.fromMessage(chat, target != null ? target : message, group));
            return true;
        }
        if (option == OPTION_TRANSLATE_HERE) {
            SolegramInlineTranslate.toggle(chat, SolegramInlineTranslate.target(message, group));
            return true;
        }
        if (option >= OPTION_PLUGIN_BASE && option < OPTION_PLUGIN_BASE + 500) {
            int index = option - OPTION_PLUGIN_BASE;
            if (index < lastMessageItems.size() && lastMessageContext != null) {
                PluginMenuItem item = lastMessageItems.get(index);
                Map<String, Object> ctx = lastMessageContext;
                AndroidUtilities.runOnUIThread(() -> PluginsController.getInstance().onMenuItemClick(item, ctx));
            }
            return true;
        }
        return false;
    }

    /** Technical details of a message: ids, exact dates, sender, forward source, file and data centre. */
    private static void showMessageDetails(ChatActivity chat, MessageObject message) {
        if (chat.getParentActivity() == null || message == null || message.messageOwner == null) {
            return;
        }
        final TLRPC.Message m = message.messageOwner;
        final java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault());
        final StringBuilder plain = new StringBuilder();
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final java.util.function.BiConsumer<Integer, Object> row = (label, value) -> {
            if (value == null || "".equals(value)) {
                return;
            }
            String name = LocaleController.getString(label);
            if (sb.length() > 0) {
                sb.append("\n");
                plain.append("\n");
            }
            sb.append(AndroidUtilities.replaceTags("**" + name + "**")).append(": ").append(String.valueOf(value));
            plain.append(name).append(": ").append(value);
        };
        row.accept(R.string.SG_DetailsId, m.id);
        row.accept(R.string.SG_DetailsChat, message.getDialogId());
        long fromId = message.getFromChatId();
        if (fromId != 0) {
            String name = null;
            if (fromId > 0) {
                TLRPC.User user = chat.getMessagesController().getUser(fromId);
                name = user != null ? org.telegram.messenger.UserObject.getUserName(user) : null;
            } else {
                TLRPC.Chat c = chat.getMessagesController().getChat(-fromId);
                name = c != null ? c.title : null;
            }
            row.accept(R.string.SG_DetailsSender, name != null ? name + " (" + fromId + ")" : String.valueOf(fromId));
        }
        if (m.date != 0) {
            row.accept(R.string.SG_DetailsDate, format.format(new java.util.Date(m.date * 1000L)));
        }
        if (m.edit_date != 0) {
            row.accept(R.string.SG_DetailsEdited, format.format(new java.util.Date(m.edit_date * 1000L)));
        }
        if (m.fwd_from != null) {
            String source = m.fwd_from.from_name;
            if (source == null && m.fwd_from.from_id != null) {
                source = String.valueOf(org.telegram.messenger.MessageObject.getPeerId(m.fwd_from.from_id));
            }
            if (m.fwd_from.date != 0) {
                source = (source != null ? source + ", " : "") + format.format(new java.util.Date(m.fwd_from.date * 1000L));
            }
            row.accept(R.string.SG_DetailsForwarded, source);
        }
        if (m.grouped_id != 0) {
            row.accept(R.string.SG_DetailsAlbum, m.grouped_id);
        }
        if (m.views > 0) {
            row.accept(R.string.SG_DetailsViews, m.views);
        }
        if (m.forwards > 0) {
            row.accept(R.string.SG_DetailsForwards, m.forwards);
        }
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            row.accept(R.string.SG_DetailsFile, org.telegram.messenger.FileLoader.getDocumentFileName(document));
            row.accept(R.string.SG_DetailsSize, AndroidUtilities.formatFileSize(document.size) + " (" + document.size + ")");
            row.accept(R.string.SG_DetailsMime, document.mime_type);
            row.accept(R.string.SG_DetailsDc, "DC" + document.dc_id);
        } else if (m.media != null && m.media.photo != null) {
            TLRPC.PhotoSize size = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(m.media.photo.sizes, AndroidUtilities.getPhotoSize());
            if (size != null) {
                row.accept(R.string.SG_DetailsSize, AndroidUtilities.formatFileSize(size.size) + " · " + size.w + "×" + size.h);
            }
            row.accept(R.string.SG_DetailsDc, "DC" + m.media.photo.dc_id);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity(), chat.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_MessageDetails));
        builder.setMessage(sb);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.setNeutralButton(LocaleController.getString(R.string.Copy), (d, w) -> {
            AndroidUtilities.addToClipboard(plain.toString());
            org.telegram.ui.Components.BulletinFactory.of(chat).createCopyBulletin(LocaleController.getString(R.string.SG_DetailsCopied)).show();
        });
        chat.showDialog(builder.create());
    }

    private static void showEditHistory(BaseFragment fragment, MessageObject message) {
        int account = fragment.getCurrentAccount();
        MessageVault.loadEditHistory(account, message, versions -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (versions.isEmpty()) {
                sb.append(LocaleController.getString(R.string.SG_EditHistoryEmpty));
            } else {
                for (MessageVault.EditVersion v : versions) {
                    sb.append(AndroidUtilities.replaceTags("**" + LocaleController.formatDateAudio(v.date, false) + "**"));
                    sb.append("\n").append(v.text == null ? "" : v.text).append("\n\n");
                }
                sb.append(AndroidUtilities.replaceTags("**" + LocaleController.getString(R.string.SG_EditHistoryCurrent) + "**"));
                sb.append("\n").append(message.messageOwner.message == null ? "" : message.messageOwner.message);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.SG_EditHistory));
            builder.setMessage(sb);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            fragment.showDialog(builder.create());
        });
    }

    private static void forgetDeleted(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group) {
        int account = chat.getCurrentAccount();
        ArrayList<Integer> ids = new ArrayList<>();
        if (group != null) {
            for (MessageObject m : group.messages) {
                ids.add(m.getId());
            }
        } else {
            ids.add(message.getId());
        }
        long scope = MessageVault.scopeOf(message.messageOwner);
        MessageVault.forget(account, scope, ids);
        long dialogId = message.getDialogId();
        // The server already deleted these messages: remove them from the local cache only.
        MessagesController.getInstance(account).deleteMessages(ids, null, null, dialogId, 0, false, 0, true);
    }
    // endregion

    // region Chat header menu

    /** Header menu item: jump to the very first message of the chat. */
    public static final int HEADER_FIRST_MESSAGE = 19990;
    /** Header menu item: message filters off / on in this chat. */
    public static final int HEADER_FILTERS = 19991;
    /** Selection action bar: a reminder made from the selected messages. */
    public static final int ACTION_REMIND = 19992;
    /** Selection action bar: the selected messages go by SoleDrop. */
    public static final int ACTION_SOLEDROP = 19993;
    /** Profile menu: the account, group or channel goes by SoleDrop. */
    public static final int PROFILE_SOLEDROP = 20990;
    /** Profile menu: the chat is hidden from the lists of this device (or shown again). */
    public static final int PROFILE_HIDE = 20991;
    private static final Map<ProfileActivity, org.telegram.ui.ActionBar.ActionBarMenuSubItem> hideItems = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static List<MessageObject> soleDropMessages(MessageObject message, MessageObject.GroupedMessages group) {
        return group != null && !group.messages.isEmpty() ? group.messages : java.util.Collections.singletonList(message);
    }
    private static final Map<ChatActivity, ActionBarMenuItem.Item> filterItems = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static void addHeaderItems(ChatActivity chat, ActionBarMenuItem headerItem) {
        headerItems.remove(chat);
        if (headerItem != null && SolegramConfig.jumpToFirstInMenu && chat.getCurrentEncryptedChat() == null) {
            headerItem.lazilyAddSubItem(HEADER_FIRST_MESSAGE, R.drawable.msg_go_up, LocaleController.getString(R.string.SG_JumpToFirst));
        }
        // shown while there is something to filter; the menu is built once per chat, so its text follows the toggle
        if (headerItem != null && chat.getChatMode() == ChatActivity.MODE_DEFAULT && SolegramMessageFilters.isActive()
            && chat.getDialogId() != chat.getUserConfig().getClientUserId()) {
            filterItems.put(chat, headerItem.lazilyAddSubItem(HEADER_FILTERS, R.drawable.msg_archive_hide, filtersHeaderText(chat)));
        }
        if (headerItem == null || !PluginsController.isEngineAvailable()) {
            return;
        }
        Map<String, Object> ctx = chatContext(chat);
        List<PluginMenuItem> items = PluginsController.getInstance().getMenuItems(PluginMenuItem.CHAT_ACTION_MENU, ctx);
        for (int i = 0; i < items.size(); i++) {
            PluginMenuItem item = items.get(i);
            int icon = item.getIconResId();
            headerItem.lazilyAddSubItem(HEADER_PLUGIN_BASE + i, icon != 0 ? icon : R.drawable.msg_bots, item.text);
        }
        headerItems.put(chat, items);
    }

    public static boolean onHeaderItem(ChatActivity chat, int id) {
        if (id == ACTION_SOLEDROP) {
            final com.solegram.messenger.soledrop.SoleDropItem item = com.solegram.messenger.soledrop.SoleDropItem.messages(chat.solegramSelectedMessages());
            chat.clearSelectionMode();
            if (item != null) {
                com.solegram.messenger.soledrop.SoleDrop.open(chat, item);
            }
            return true;
        }
        if (id == ACTION_REMIND) {
            final ArrayList<MessageObject> selected = chat.solegramSelectedMessages();
            chat.clearSelectionMode();
            com.solegram.messenger.ui.SolegramReminderSheet.show(chat, selected);
            return true;
        }
        if (id == HEADER_FILTERS) {
            toggleFiltersHere(chat);
            return true;
        }
        if (id == HEADER_FIRST_MESSAGE) {
            // date 1 = the beginning of time: Telegram loads from the oldest message
            chat.jumpToDate(1);
            return true;
        }
        if (id < HEADER_PLUGIN_BASE || id >= HEADER_PLUGIN_BASE + 500) {
            return false;
        }
        int index = id - HEADER_PLUGIN_BASE;
        List<PluginMenuItem> items = headerItems.get(chat);
        if (items != null && index < items.size()) {
            Map<String, Object> ctx = chatContext(chat);
            PluginsController.getInstance().onMenuItemClick(items.get(index), ctx);
        }
        return true;
    }

    private static String filtersHeaderText(ChatActivity chat) {
        return LocaleController.getString(SolegramMessageFilters.isChatExcluded(chat.getDialogId()) ? R.string.SG_FilterOnHere : R.string.SG_FilterOffHere);
    }

    /** Message filters off (or back on) in one chat. */
    public static void toggleFiltersHere(ChatActivity chat) {
        final long dialogId = chat.getDialogId();
        final boolean off = !SolegramMessageFilters.isChatExcluded(dialogId);
        SolegramMessageFilters.setChatExcluded(dialogId, off);
        final ActionBarMenuItem.Item item = filterItems.get(chat);
        if (item != null) {
            item.setText(filtersHeaderText(chat));
        }
        chat.solegramFiltersChanged();
        org.telegram.ui.Components.BulletinFactory.of(chat).createSimpleBulletin(R.raw.info,
            LocaleController.getString(off ? R.string.SG_FilterOffHereDone : R.string.SG_FilterOnHereDone)).show();
    }
    // endregion

    // region Profile menu

    public static Map<String, Object> profileContext(ProfileActivity profile, long userId, long chatId, TLRPC.UserFull userFull, TLRPC.ChatFull chatFull) {
        Map<String, Object> ctx = SolegramUi.baseContext(profile);
        MessagesController mc = MessagesController.getInstance(profile.getCurrentAccount());
        if (userId != 0) {
            ctx.put("userId", userId);
            ctx.put("user", mc.getUser(userId));
            ctx.put("userFull", userFull);
            ctx.put("dialog_id", userId);
        }
        if (chatId != 0) {
            ctx.put("chatId", chatId);
            ctx.put("chat", mc.getChat(chatId));
            ctx.put("chatFull", chatFull);
            ctx.put("dialog_id", -chatId);
        }
        return ctx;
    }

    public static void addProfileItems(ProfileActivity profile, ActionBarMenuItem otherItem, Map<String, Object> ctx) {
        profileItems.remove(profile);
        profileContexts.put(profile, ctx);
        if (otherItem != null && ctx.get("dialog_id") instanceof Long && (Long) ctx.get("dialog_id") != 0) {
            otherItem.addSubItem(PROFILE_SOLEDROP, R.drawable.msg_soledrop, LocaleController.getString(R.string.SG_SoleDropSendVia));
            final long dialogId = (Long) ctx.get("dialog_id");
            final int account = profile.getCurrentAccount();
            hideItems.remove(profile);
            if (SolegramHiddenChats.isHidden(account, dialogId)) {
                hideItems.put(profile, otherItem.addSubItem(PROFILE_HIDE, R.drawable.msg_archive_show, LocaleController.getString(R.string.SG_UnhideChat)));
            } else if (SolegramHiddenChats.canHide(account, dialogId)) {
                hideItems.put(profile, otherItem.addSubItem(PROFILE_HIDE, R.drawable.msg_archive_hide, LocaleController.getString(R.string.SG_HideChat)));
            }
        }
        if (otherItem == null || !PluginsController.isEngineAvailable()) {
            return;
        }
        profileContexts.put(profile, ctx);
        List<PluginMenuItem> items = PluginsController.getInstance().getMenuItems(PluginMenuItem.PROFILE_ACTION_MENU, ctx);
        for (int i = 0; i < items.size(); i++) {
            PluginMenuItem item = items.get(i);
            int icon = item.getIconResId();
            otherItem.addSubItem(PROFILE_PLUGIN_BASE + i, icon != 0 ? icon : R.drawable.msg_bots, item.text);
        }
        profileItems.put(profile, items);
    }

    public static boolean onProfileItem(ProfileActivity profile, int id) {
        if (id == PROFILE_HIDE) {
            final Map<String, Object> ctx = profileContexts.get(profile);
            if (ctx != null && ctx.get("dialog_id") instanceof Long) {
                toggleHidden(profile, (Long) ctx.get("dialog_id"));
            }
            return true;
        }
        if (id == PROFILE_SOLEDROP) {
            final Map<String, Object> ctx = profileContexts.get(profile);
            if (ctx != null && ctx.get("dialog_id") instanceof Long) {
                com.solegram.messenger.soledrop.SoleDrop.open(profile,
                    com.solegram.messenger.soledrop.SoleDropItem.peer(profile.getCurrentAccount(), (Long) ctx.get("dialog_id")));
            }
            return true;
        }
        if (id < PROFILE_PLUGIN_BASE || id >= PROFILE_PLUGIN_BASE + 500) {
            return false;
        }
        int index = id - PROFILE_PLUGIN_BASE;
        List<PluginMenuItem> items = profileItems.get(profile);
        Map<String, Object> ctx = profileContexts.get(profile);
        if (items != null && ctx != null && index < items.size()) {
            PluginsController.getInstance().onMenuItemClick(items.get(index), ctx);
        }
        return true;
    }
    // endregion

    /** Hides the chat of a profile, or brings it back to the list; the menu item follows. */
    private static void toggleHidden(ProfileActivity profile, long dialogId) {
        final int account = profile.getCurrentAccount();
        final boolean hide = !SolegramHiddenChats.isHidden(account, dialogId);
        if (hide) {
            final ArrayList<Long> ids = new ArrayList<>();
            ids.add(dialogId);
            if (SolegramHiddenChats.hide(account, ids) == 0) {
                return;
            }
        } else {
            SolegramHiddenChats.unhide(account, dialogId);
        }
        final org.telegram.ui.ActionBar.ActionBarMenuSubItem item = hideItems.get(profile);
        if (item != null) {
            item.setTextAndIcon(LocaleController.getString(hide ? R.string.SG_UnhideChat : R.string.SG_HideChat),
                hide ? R.drawable.msg_archive_show : R.drawable.msg_archive_hide);
        }
        final org.telegram.ui.Components.BulletinFactory bulletins = org.telegram.ui.Components.BulletinFactory.of(profile);
        if (hide) {
            bulletins.createSimpleBulletin(R.raw.chats_hide, LocaleController.getString(R.string.SG_HiddenChatDone),
                LocaleController.getString(SolegramHiddenChats.gestureEnabled() ? R.string.SG_HiddenOpenHint : R.string.SG_HiddenOpenHintSettings),
                LocaleController.getString(R.string.Undo), () -> toggleHidden(profile, dialogId)).show();
        } else {
            bulletins.createSimpleBulletin(R.raw.chats_unhide, LocaleController.getString(R.string.SG_HiddenChatShown),
                LocaleController.getString(R.string.Undo), () -> toggleHidden(profile, dialogId)).show();
        }
    }

    static boolean isEmpty(CharSequence s) {
        return TextUtils.isEmpty(s);
    }
}
