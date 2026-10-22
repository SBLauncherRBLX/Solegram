/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Stable, simple entry points for the plugin SDK (client_utils.send_* / edit_message), so the
 * Python side does not depend on Telegram's long and frequently changing method signatures.
 */
public final class PluginApi {

    private PluginApi() {
    }

    /** A lightweight MessageObject usable as reply target when only the id is known. */
    public static MessageObject replyStub(int account, long peer, int messageId) {
        if (messageId <= 0) {
            return null;
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = messageId;
        message.peer_id = MessagesController.getInstance(account).getPeer(peer);
        message.dialog_id = peer;
        return new MessageObject(account, message, false, false);
    }

    public static SendMessagesHelper.SendMessageParams textParams(long peer, String text) {
        return SendMessagesHelper.SendMessageParams.of(text, peer);
    }

    public static void send(int account, SendMessagesHelper.SendMessageParams params) {
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(account).sendMessage(params));
    }

    public static void sendPhoto(int account, long peer, String path, String caption, ArrayList<TLRPC.MessageEntity> entities,
                                 boolean highQuality, MessageObject replyTo, boolean notify, int scheduleDate, boolean spoiler) {
        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
            TLRPC.TL_photo photo = helper.generatePhotoSizes(null, path, null, highQuality);
            if (photo == null) {
                sendDocument(account, peer, path, caption, entities, replyTo, notify, scheduleDate);
                return;
            }
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(photo, path, peer, replyTo, null, caption, entities, null, null, notify, scheduleDate, 0, 0, null, false);
            params.hasMediaSpoilers = spoiler;
            helper.sendMessage(params);
        });
    }

    public static void sendDocument(int account, long peer, String path, String caption, ArrayList<TLRPC.MessageEntity> entities,
                                    MessageObject replyTo, boolean notify, int scheduleDate) {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            paths.add(path);
            SendMessagesHelper.prepareSendingDocuments(AccountInstance.getInstance(account), paths, paths, null, caption, entities, null, peer,
                replyTo, null, null, null, null, notify, scheduleDate, 0, null, null, 0, false, 0, 0, null);
        });
    }

    public static void sendVideo(int account, long peer, String path, String caption, ArrayList<TLRPC.MessageEntity> entities,
                                 MessageObject replyTo, boolean notify, int scheduleDate, boolean spoiler) {
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(account), path, null, null, null, peer,
            replyTo, null, null, null, entities, 0, null, notify, scheduleDate, 0, false, spoiler, caption, null, 0, 0));
    }

    /** Edits the text/caption of a message. */
    public static void editText(MessageObject messageObject, String text, ArrayList<TLRPC.MessageEntity> entities) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            SendMessagesHelper.getInstance(messageObject.currentAccount).editMessage(messageObject, text, false, fragment, entities, 0, 0);
        });
    }

    /** Replaces the media of a message with a photo or a document file. */
    public static void editMedia(MessageObject messageObject, String path, String caption, ArrayList<TLRPC.MessageEntity> entities, boolean spoiler) {
        AndroidUtilities.runOnUIThread(() -> {
            if (caption != null) {
                messageObject.editingMessage = caption;
                messageObject.editingMessageEntities = entities;
            }
            SendMessagesHelper helper = SendMessagesHelper.getInstance(messageObject.currentAccount);
            String lower = path.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                TLRPC.TL_photo photo = helper.generatePhotoSizes(null, path, null, false);
                if (photo != null) {
                    helper.editMessage(messageObject, photo, null, null, path, null, null, false, spoiler, null);
                    return;
                }
            }
            ArrayList<String> paths = new ArrayList<>();
            paths.add(path);
            SendMessagesHelper.prepareSendingDocuments(AccountInstance.getInstance(messageObject.currentAccount), paths, paths, null,
                TextUtils.isEmpty(caption) ? null : caption, entities, null, messageObject.getDialogId(), null, null, null, null, messageObject,
                true, 0, 0, null, null, 0, false, 0, 0, null);
        });
    }

    public static long dialogIdOf(TLRPC.Peer peer) {
        return DialogObject.getPeerDialogId(peer);
    }

    public static int selectedAccount() {
        return UserConfig.selectedAccount;
    }
}
