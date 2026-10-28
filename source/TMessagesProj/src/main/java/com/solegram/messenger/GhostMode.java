/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Ghost mode: hides typing, read receipts, online status and story views.
 * Works at the request level, so it covers every place in the app that sends these requests.
 */
public final class GhostMode {

    /** Requests created by Solegram itself that must pass the filter (e.g. "read on interact"). */
    private static final Set<TLObject> allowed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private GhostMode() {
    }

    /**
     * @return the request to send (possibly modified) or {@code null} to drop it silently.
     */
    public static TLObject filter(int account, TLObject request) {
        if (allowed.remove(request)) {
            return request;
        }
        if (SolegramConfig.ghostNoTyping) {
            if (request instanceof TLRPC.TL_messages_setTyping || request instanceof TLRPC.TL_messages_setEncryptedTyping) {
                return null;
            }
        }
        if (SolegramConfig.ghostNoRead) {
            if (request instanceof TLRPC.TL_messages_readHistory
                || request instanceof TLRPC.TL_channels_readHistory
                || request instanceof TLRPC.TL_messages_readDiscussion
                || request instanceof TLRPC.TL_messages_readMessageContents
                || request instanceof TLRPC.TL_channels_readMessageContents
                || request instanceof TLRPC.TL_messages_readEncryptedHistory) {
                return null;
            }
        }
        if (SolegramConfig.ghostNoStoryRead) {
            if (request instanceof TL_stories.TL_stories_readStories || request instanceof TL_stories.TL_stories_incrementStoryViews) {
                return null;
            }
        }
        if (SolegramConfig.ghostOffline && request instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) request).offline = true;
        }
        return request;
    }

    public static boolean isSendRequest(TLObject request) {
        return request instanceof TLRPC.TL_messages_sendMessage
            || request instanceof TLRPC.TL_messages_sendMedia
            || request instanceof TLRPC.TL_messages_sendMultiMedia
            || request instanceof TLRPC.TL_messages_sendInlineBotResult
            || request instanceof TLRPC.TL_messages_forwardMessages;
    }

    /** Accounts and dialogs waiting for the follow-up of {@link #afterSend}; UI thread only. */
    private static final HashSet<Integer> offlinePending = new HashSet<>();
    private static final HashSet<String> readPending = new HashSet<>();
    /** The follow-ups of a burst of sends (an album, a forward of many messages) go out once, after it. */
    private static final long AFTER_SEND_DELAY = 400;

    /**
     * Called after an outgoing message request completed. That is on the network thread, so the follow-up
     * requests are made on the UI thread (where the dialogs live), and once per burst instead of once per message.
     */
    public static void afterSend(int account, TLObject request) {
        if (SolegramConfig.ghostOffline) {
            // The server marks the account online after any activity: push it back offline.
            AndroidUtilities.runOnUIThread(() -> {
                if (offlinePending.add(account)) {
                    AndroidUtilities.runOnUIThread(() -> {
                        offlinePending.remove(account);
                        sendOffline(account);
                    }, AFTER_SEND_DELAY);
                }
            });
        }
        if (SolegramConfig.ghostNoRead && SolegramConfig.ghostReadOnInteract) {
            TLRPC.InputPeer peer = null;
            if (request instanceof TLRPC.TL_messages_sendMessage) {
                peer = ((TLRPC.TL_messages_sendMessage) request).peer;
            } else if (request instanceof TLRPC.TL_messages_sendMedia) {
                peer = ((TLRPC.TL_messages_sendMedia) request).peer;
            } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
                peer = ((TLRPC.TL_messages_sendMultiMedia) request).peer;
            } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult) {
                peer = ((TLRPC.TL_messages_sendInlineBotResult) request).peer;
            }
            if (peer != null) {
                final long dialogId = DialogObject.getPeerDialogId(peer);
                final String key = account + "_" + dialogId;
                AndroidUtilities.runOnUIThread(() -> {
                    if (readPending.add(key)) {
                        AndroidUtilities.runOnUIThread(() -> {
                            readPending.remove(key);
                            readDialog(account, dialogId);
                        }, AFTER_SEND_DELAY);
                    }
                });
            }
        }
    }

    /** Marks a dialog as read on the server even while read receipts are hidden. */
    public static void readDialog(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        int maxId = dialog != null ? dialog.top_message : 0;
        if (maxId <= 0) {
            return;
        }
        TLObject req;
        if (DialogObject.isChatDialog(dialogId) && ChatObjectCompat.isChannel(controller, -dialogId)) {
            TLRPC.TL_channels_readHistory r = new TLRPC.TL_channels_readHistory();
            r.channel = controller.getInputChannel(-dialogId);
            r.max_id = maxId;
            req = r;
        } else if (DialogObject.isEncryptedDialog(dialogId)) {
            return;
        } else {
            TLRPC.TL_messages_readHistory r = new TLRPC.TL_messages_readHistory();
            r.peer = controller.getInputPeer(dialogId);
            r.max_id = maxId;
            req = r;
        }
        allowed.add(req);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_messages_affectedMessages) {
                TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                controller.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
            }
        });
    }

    public static void sendOffline(int account) {
        TL_account.updateStatus req = new TL_account.updateStatus();
        req.offline = true;
        ConnectionsManager.getInstance(account).sendRequest(req, null);
    }

    public static void onGhostModeChanged() {
        if (SolegramConfig.ghostOffline) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    sendOffline(a);
                }
            }
        }
    }

    /** Small indirection so this class does not depend on ChatObject internals. */
    static final class ChatObjectCompat {
        static boolean isChannel(MessagesController controller, long chatId) {
            TLRPC.Chat chat = controller.getChat(chatId);
            return org.telegram.messenger.ChatObject.isChannel(chat);
        }
    }

    public static AccountInstance account(int account) {
        return AccountInstance.getInstance(account);
    }
}
