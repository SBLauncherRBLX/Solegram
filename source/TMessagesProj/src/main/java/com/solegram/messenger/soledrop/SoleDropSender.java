/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.util.ArrayList;

/**
 * After the tap: the sending phone sends the item to the user it found, through Telegram itself. Messages are
 * forwarded, accounts and chats go as a link or a contact, photos and the location as messages; Stars and gifts
 * open Telegram's own sheets aimed at that user (the choice, the price and the confirmation stay Telegram's).
 * The sending is followed until Telegram confirms it (the server got the message, or the gift's service message
 * came), so the ring on the screen fills when it really went.
 */
public final class SoleDropSender implements NotificationCenter.NotificationCenterDelegate {

    public interface Listener {
        /** Telegram's own sheet is open for the user to choose and confirm (Stars, a gift). */
        void onSheetOpened();

        /** A part went through (0..1). */
        void onProgress(float progress);

        void onDone();

        /** Did not go (an error, or the user closed Telegram's sheet); {@code reason} may be null for a plain cancel. */
        void onFailed(@Nullable CharSequence reason);
    }

    private static final long TIMEOUT = 45_000, MEDIA_TIMEOUT = 180_000, SHEET_TIMEOUT = 20_000;

    private final int account;
    private final SoleDropItem item;
    private final TLRPC.User receiver;
    private final Listener listener;
    private int expected = 1, confirmed;
    private boolean finished, sheetPurchased;
    private BottomSheet sheet;

    private SoleDropSender(int account, SoleDropItem item, TLRPC.User receiver, Listener listener) {
        this.account = account;
        this.item = item;
        this.receiver = receiver;
        this.listener = listener;
    }

    /**
     * Why this item cannot go to anyone as it is, or null when it can (checked before the tap, so the screen can
     * say it at once).
     */
    @Nullable
    public static CharSequence problem(int account, SoleDropItem item) {
        if (item.type == SoleDropProtocol.ITEM_PEER && shareText(account, item) == null && contactUser(account, item) == null) {
            return LocaleController.getString(R.string.SG_SoleDropNoLink);
        }
        if (item.type == SoleDropProtocol.ITEM_MESSAGES) {
            for (MessageObject m : item.messages) {
                final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-m.getDialogId());
                if (m.messageOwner.noforwards || chat != null && MessagesController.getInstance(account).isChatNoForwards(chat)) {
                    return LocaleController.getString(R.string.SG_SoleDropNoForwards);
                }
            }
        }
        return null;
    }

    /** Starts sending; the returned object follows it until it ends (or {@link #cancel()}). */
    public static SoleDropSender send(BaseFragment fragment, int account, SoleDropItem item, TLRPC.User receiver, Listener listener) {
        final SoleDropSender sender = new SoleDropSender(account, item, receiver, listener);
        sender.start(fragment);
        return sender;
    }

    private void start(BaseFragment fragment) {
        final long peer = receiver.id;
        final MessagesController mc = MessagesController.getInstance(account);
        final SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
        // a receiver who takes Stars for messages: only the chat may ask that (Telegram's own confirmation)
        if (!item.usesTelegramSheet() && !item.isNameDrop() && mc.getSendPaidMessagesStars(peer) > 0) {
            fail(LocaleController.getString(R.string.SG_SoleDropPaidMessages));
            return;
        }
        observe(true);
        switch (item.type) {
            case SoleDropProtocol.ITEM_MY_ACCOUNT:
                // the contact exchange needs no message: the other phone has this account's token already
                AndroidUtilities.runOnUIThread(this::done, 450);
                return;
            case SoleDropProtocol.ITEM_MESSAGES: {
                expected = item.messages.size();
                final int result = helper.sendMessage(new ArrayList<>(item.messages), peer, false, false, true, 0, 0);
                if (result != 0) {
                    fail(LocaleController.getString(R.string.SG_SoleDropSendFailed));
                    return;
                }
                break;
            }
            case SoleDropProtocol.ITEM_PEER:
            case SoleDropProtocol.ITEM_CONTACT: {
                final String text = shareText(account, item);
                if (text != null) {
                    helper.sendMessage(SendMessagesHelper.SendMessageParams.of(text, peer, null, null, null, true, null, null, null, true, 0, 0, null, false));
                } else {
                    final TLRPC.User user = contactUser(account, item);
                    if (user == null) {
                        fail(LocaleController.getString(R.string.SG_SoleDropNoLink));
                        return;
                    }
                    helper.sendMessage(SendMessagesHelper.SendMessageParams.of(user, peer, null, null, null, null, true, 0, 0));
                }
                break;
            }
            case SoleDropProtocol.ITEM_LINK:
            case SoleDropProtocol.ITEM_STICKERS: {
                final String text = item.type == SoleDropProtocol.ITEM_STICKERS ? stickersLink(account, item) : item.text;
                if (TextUtils.isEmpty(text)) {
                    fail(null);
                    return;
                }
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(text, peer, null, null, null, true, null, null, null, true, 0, 0, null, false));
                break;
            }
            case SoleDropProtocol.ITEM_LOCATION: {
                final TLRPC.TL_messageMediaGeo media = new TLRPC.TL_messageMediaGeo();
                media.geo = new TLRPC.TL_geoPoint();
                media.geo.lat = AndroidUtilities.fixLocationCoord(item.latitude);
                media.geo._long = AndroidUtilities.fixLocationCoord(item.longitude);
                if (item.accuracy > 0) {
                    media.geo.accuracy_radius = Math.round(item.accuracy);
                    media.geo.flags |= 1;
                }
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(media, peer, null, null, null, null, true, 0, 0));
                break;
            }
            case SoleDropProtocol.ITEM_PHOTO:
                if (item.photo == null) {
                    fail(null);
                    return;
                }
                SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(account), null, item.photo, peer, null, null, null, null, null, null,
                    null, 0, null, true, 0, 0, SendMessageChatArguments.EMPTY);
                AndroidUtilities.runOnUIThread(timeout, MEDIA_TIMEOUT);
                listener.onProgress(0.15f);
                return;
            case SoleDropProtocol.ITEM_STARS:
                openStars(fragment);
                return;
            case SoleDropProtocol.ITEM_GIFT:
                openGift(fragment);
                return;
            default:
                fail(null);
                return;
        }
        listener.onProgress(0.15f);
        AndroidUtilities.runOnUIThread(timeout, TIMEOUT);
    }

    // region Telegram's sheets

    private void openStars(BaseFragment fragment) {
        final StarsIntroActivity.GiftStarsSheet starsSheet = new StarsIntroActivity.GiftStarsSheet(fragment.getParentActivity(), fragment.getResourceProvider(), receiver, () -> {
            sheetPurchased = true;
            done();
        });
        showSheet(fragment, starsSheet);
    }

    private void openGift(BaseFragment fragment) {
        // Telegram's gift sheet: gifts, Premium, and (tab "My gifts") handing over a collectible with its own confirmation
        final GiftSheet giftSheet = new GiftSheet(fragment.getParentActivity(), account, receiver.id, closeParent -> {
            // the sheet says it finished (sent or handed over): the gift's service message follows
            sheetPurchased = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            AndroidUtilities.runOnUIThread(timeout, SHEET_TIMEOUT);
        });
        showSheet(fragment, giftSheet);
    }

    private void showSheet(BaseFragment fragment, BottomSheet bottomSheet) {
        sheet = bottomSheet;
        bottomSheet.setOnHideListener(d -> {
            sheet = null;
            if (finished) {
                return;
            }
            if (!sheetPurchased) {
                // a moment for a purchase that is still being confirmed
                AndroidUtilities.runOnUIThread(() -> {
                    if (!finished && !sheetPurchased) {
                        fail(null);
                    }
                }, 1200);
            }
        });
        listener.onSheetOpened();
        fragment.showDialog(bottomSheet);
    }
    // endregion

    // region Following the sending

    private final Runnable timeout = () -> {
        if (!finished) {
            if (sheetPurchased) {
                done(); // Telegram said it went; its service message was just late
            } else {
                fail(LocaleController.getString(R.string.SG_SoleDropNoConfirmation));
            }
        }
    };

    private void observe(boolean on) {
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        if (on) {
            nc.addObserver(this, NotificationCenter.messageReceivedByServer);
            nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        } else {
            nc.removeObserver(this, NotificationCenter.messageReceivedByServer);
            nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (finished) {
            return;
        }
        if (id == NotificationCenter.messageReceivedByServer) {
            if (args.length > 3 && args[3] instanceof Long && (Long) args[3] == receiver.id && !(args.length > 6 && Boolean.TRUE.equals(args[6]))) {
                confirmed++;
                if (confirmed >= expected) {
                    done();
                } else {
                    listener.onProgress(0.15f + 0.85f * confirmed / expected);
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (!item.usesTelegramSheet() || args.length < 2 || !(args[0] instanceof Long) || (Long) args[0] != receiver.id || !(args[1] instanceof ArrayList)) {
                return;
            }
            if (args.length > 2 && Boolean.TRUE.equals(args[2])) {
                return; // scheduled
            }
            for (Object o : (ArrayList<?>) args[1]) {
                if (o instanceof MessageObject && ((MessageObject) o).isOut() && ((MessageObject) o).messageOwner.action != null) {
                    done(); // the gift (or Stars) arrived in the chat as Telegram's service message
                    return;
                }
            }
        }
    }

    private void done() {
        if (finished) {
            return;
        }
        finish();
        listener.onDone();
    }

    private void fail(@Nullable CharSequence reason) {
        if (finished) {
            return;
        }
        finish();
        listener.onFailed(reason);
    }

    private void finish() {
        finished = true;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        observe(false);
    }

    /** The screen went away: nothing more is reported (what was sent stays sent). */
    public void cancel() {
        if (!finished) {
            finish();
        }
    }
    // endregion

    // region What an account or chat is shared as

    /** A link to a user or chat with a public username, or a chat's own invite link; null when there is none. */
    @Nullable
    static String shareText(int account, SoleDropItem item) {
        final MessagesController mc = MessagesController.getInstance(account);
        final String prefix = "https://" + mc.linkPrefix + "/";
        if (item.peerId > 0) {
            final TLRPC.User user = mc.getUser(item.peerId);
            final String username = user != null ? UserObject.getPublicUsername(user) : null;
            return username != null ? prefix + username : null;
        }
        final TLRPC.Chat chat = mc.getChat(-item.peerId);
        if (chat == null) {
            return null;
        }
        final String username = ChatObject.getPublicUsername(chat);
        if (username != null) {
            return prefix + username;
        }
        final TLRPC.ChatFull full = mc.getChatFull(chat.id);
        if (full != null && full.exported_invite != null && !TextUtils.isEmpty(full.exported_invite.link)) {
            return full.exported_invite.link;
        }
        return null;
    }

    /** A user without a username goes as a contact card, when their phone number is known to this account. */
    @Nullable
    static TLRPC.User contactUser(int account, SoleDropItem item) {
        if (item.peerId <= 0) {
            return null;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(item.peerId);
        return user != null && !TextUtils.isEmpty(user.phone) ? user : null;
    }

    @Nullable
    private static String stickersLink(int account, SoleDropItem item) {
        if (item.stickerSet == null || TextUtils.isEmpty(item.stickerSet.short_name)) {
            return null;
        }
        return "https://" + MessagesController.getInstance(account).linkPrefix + (item.stickerSet.emojis ? "/addemoji/" : "/addstickers/") + item.stickerSet.short_name;
    }
    // endregion
}
