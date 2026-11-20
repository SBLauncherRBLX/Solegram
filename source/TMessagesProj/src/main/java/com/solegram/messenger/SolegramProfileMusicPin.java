/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.Utilities;

import java.io.File;

/**
 * Solegram: puts a track first in the own profile's music (Telegram's music on the profile), from the player. A track
 * of a message is saved as it is (its file reference renewed once if it went stale); a track that is only a file on
 * the phone is uploaded first, as Telegram's own "add to profile" does.
 */
public final class SolegramProfileMusicPin {

    private SolegramProfileMusicPin() {
    }

    /** {@code done}: null when it worked, else the error to show (on the UI thread). */
    public static void pin(int account, @Nullable MessageObject track, Utilities.Callback<String> done) {
        final TLRPC.Document document = track != null ? track.getDocument() : null;
        if (document == null) {
            done.run("NO_TRACK");
            return;
        }
        if (document.id != 0) {
            save(account, track, document, false, done);
            return;
        }
        // a file on the phone only: uploaded to the own chat first
        final String path = track.messageOwner != null ? track.messageOwner.attachPath : null;
        final File file = path != null ? new File(path) : null;
        if (file == null || !file.exists()) {
            done.run("FILE_NOT_FOUND");
            return;
        }
        FileLoader.getInstance(account).uploadFile(file.getAbsolutePath(), input -> {
            if (input == null) {
                AndroidUtilities.runOnUIThread(() -> done.run("UPLOAD_FAILED"));
                return;
            }
            final TLRPC.TL_messages_uploadMedia req = new TLRPC.TL_messages_uploadMedia();
            req.peer = MessagesController.getInstance(account).getInputPeer(UserConfig.getInstance(account).getClientUserId());
            final TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
            media.file = input;
            media.mime_type = document.mime_type;
            media.attributes.addAll(document.attributes);
            req.media = media;
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
                if (res instanceof TLRPC.TL_messageMediaDocument && ((TLRPC.TL_messageMediaDocument) res).document != null) {
                    save(account, null, ((TLRPC.TL_messageMediaDocument) res).document, true, done);
                } else {
                    AndroidUtilities.runOnUIThread(() -> done.run(err != null ? err.text : "UPLOAD_FAILED"));
                }
            });
        });
    }

    /** account.saveMusic without "after": the track goes first, so the profile shows it. */
    private static void save(int account, @Nullable MessageObject track, TLRPC.Document document, boolean triedFileRef, Utilities.Callback<String> done) {
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = false;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = document.id;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (err != null && FileRefController.isFileRefError(err.text) && !triedFileRef && track != null && track.getId() > 0) {
                renew(account, track, fresh -> {
                    final TLRPC.Document doc = fresh != null ? fresh.getDocument() : null;
                    if (doc != null) {
                        save(account, fresh, doc, true, done);
                    } else {
                        AndroidUtilities.runOnUIThread(() -> done.run(err.text));
                    }
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (err != null) {
                    done.run(err.text);
                    return;
                }
                MessagesController.getInstance(account).getSavedMusicIds().update(document.id, true);
                final long selfId = UserConfig.getInstance(account).getClientUserId();
                final TLRPC.UserFull userInfo = MessagesController.getInstance(account).getUserFull(selfId);
                if (userInfo != null) {
                    userInfo.flags2 |= TLObject.FLAG_21;
                    userInfo.saved_music = document;
                    MessagesStorage.getInstance(account).updateUserInfo(userInfo, true);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated, selfId);
                done.run(null);
            });
        });
    }

    /** The message again from the server: its file reference is fresh. */
    private static void renew(int account, MessageObject track, Utilities.Callback<MessageObject> done) {
        final int id = track.getId();
        final TLObject req;
        if (track.getDialogId() >= 0) {
            final TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(id);
            req = r;
        } else {
            final TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInstance(account).getInputChannel(-track.getDialogId());
            r.id.add(id);
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            TLRPC.Message message = null;
            if (res instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) res).messages) {
                    if (m.id == id) {
                        message = m;
                        break;
                    }
                }
            }
            done.run(message != null ? new MessageObject(account, message, false, true) : null);
        });
    }
}
