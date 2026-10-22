/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import com.solegram.messenger.plugins.HookResult;
import com.solegram.messenger.plugins.PluginsController;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;

/**
 * Single entry point for every Solegram hook placed inside Telegram code.
 * Each method is cheap when nothing is enabled, because it is called for every request/update.
 */
public final class SolegramHooks {

    private SolegramHooks() {
    }

    // region Requests

    /** @return request to send, or {@code null} to drop it. */
    public static TLObject preRequest(int account, TLObject request) {
        if (request == null) {
            return null;
        }
        TLObject result = request;
        try {
            if (SolegramConfig.isGhostModeActive()) {
                result = GhostMode.filter(account, result);
                if (result == null) {
                    return null;
                }
            }
            if (PluginsController.hasRequestHooks()) {
                HookResult r = PluginsController.getInstance().dispatchPreRequest(account, result);
                if (r != null) {
                    if (r.isCancel()) {
                        return null;
                    }
                    if (r.isModify() && r.request instanceof TLObject) {
                        result = (TLObject) r.request;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    public static final class PostResult {
        public TLObject response;
        public TLRPC.TL_error error;
        public boolean cancel;
    }

    /** @return {@code null} when nothing changed. */
    public static PostResult postRequest(int account, TLObject request, TLObject response, TLRPC.TL_error error) {
        try {
            if (error == null && GhostMode.isSendRequest(request) && SolegramConfig.isGhostModeActive()) {
                GhostMode.afterSend(account, request);
            }
            if (PluginsController.hasRequestHooks()) {
                HookResult r = PluginsController.getInstance().dispatchPostRequest(account, request, response, error);
                if (r != null && !r.isDefault()) {
                    PostResult p = new PostResult();
                    p.response = response;
                    p.error = error;
                    if (r.isCancel()) {
                        p.cancel = true;
                    } else {
                        if (r.response instanceof TLObject) {
                            p.response = (TLObject) r.response;
                        }
                        if (r.error instanceof TLRPC.TL_error) {
                            p.error = (TLRPC.TL_error) r.error;
                        }
                    }
                    return p;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }
    // endregion

    // region Updates

    public static TLRPC.Updates processUpdates(int account, TLRPC.Updates updates) {
        if (updates == null || !PluginsController.hasUpdateHooks()) {
            return updates;
        }
        try {
            HookResult r = PluginsController.getInstance().dispatchUpdates(account, updates);
            if (r != null) {
                if (r.isCancel()) {
                    return null;
                }
                if (r.isModify() && r.updates instanceof TLRPC.Updates) {
                    return (TLRPC.Updates) r.updates;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return updates;
    }

    public static ArrayList<TLRPC.Update> processUpdateArray(int account, ArrayList<TLRPC.Update> updates) {
        if (updates == null || updates.isEmpty()) {
            return updates;
        }
        final boolean keepDeleted = SolegramConfig.saveDeletedMessages;
        final boolean keepEdits = SolegramConfig.saveEditsHistory;
        final boolean pluginHooks = PluginsController.hasUpdateHooks();
        if (!keepDeleted && !keepEdits && !pluginHooks) {
            return updates;
        }
        ArrayList<TLRPC.Update> result = null;
        for (int i = 0, n = updates.size(); i < n; i++) {
            TLRPC.Update update = updates.get(i);
            TLRPC.Update out = update;
            try {
                if (pluginHooks) {
                    HookResult r = PluginsController.getInstance().dispatchUpdate(account, update);
                    if (r != null) {
                        if (r.isCancel()) {
                            out = null;
                        } else if (r.isModify() && r.update instanceof TLRPC.Update) {
                            out = (TLRPC.Update) r.update;
                        }
                    }
                }
                if (out != null && keepDeleted) {
                    if (out instanceof TL_update.TL_updateDeleteMessages) {
                        MessageVault.markDeleted(account, 0, ((TL_update.TL_updateDeleteMessages) out).messages);
                        out = null;
                    } else if (out instanceof TL_update.TL_updateDeleteChannelMessages) {
                        TL_update.TL_updateDeleteChannelMessages del = (TL_update.TL_updateDeleteChannelMessages) out;
                        MessageVault.markDeleted(account, -del.channel_id, del.messages);
                        out = null;
                    }
                }
                if (out != null && keepEdits) {
                    if (out instanceof TL_update.TL_updateEditMessage) {
                        MessageVault.captureBeforeEdit(account, ((TL_update.TL_updateEditMessage) out).message);
                    } else if (out instanceof TL_update.TL_updateEditChannelMessage) {
                        MessageVault.captureBeforeEdit(account, ((TL_update.TL_updateEditChannelMessage) out).message);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                out = update;
            }
            if (out != update && result == null) {
                result = new ArrayList<>(n);
                for (int j = 0; j < i; j++) {
                    result.add(updates.get(j));
                }
            }
            if (result != null && out != null) {
                result.add(out);
            }
        }
        return result != null ? result : updates;
    }
    // endregion

    // region Messages

    /** Params already confirmed by the user ("ask before sending"), let them through once. */
    private static final java.util.Set<SendMessagesHelper.SendMessageParams> confirmed =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    /** "Send with sound" from the send menu beats "send silently" for that chat for a few seconds. */
    private static volatile long soundAllowedUntil;
    private static volatile long soundAllowedDialog;

    public static void allowSoundOnce(long dialogId) {
        soundAllowedDialog = dialogId;
        soundAllowedUntil = android.os.SystemClock.elapsedRealtime() + 10_000;
    }

    private static boolean soundAllowed(long dialogId) {
        return android.os.SystemClock.elapsedRealtime() <= soundAllowedUntil && soundAllowedDialog == dialogId;
    }

    /** Forwards bypass {@link #onSendMessage}; SendMessagesHelper asks here whether to send them silently. */
    public static boolean silenceForward(long peer) {
        return SolegramConfig.sendSilently && !soundAllowed(peer);
    }

    public static SendMessagesHelper.SendMessageParams onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        if (params == null) {
            return null;
        }
        // a params object coming back from our own confirmation dialog was already decided on
        if (!confirmed.remove(params)) {
            if (SolegramConfig.sendSilently && !soundAllowed(params.peer)) {
                params.notify = false;
            }
            if (needsConfirmation(params) && askToSend(account, params)) {
                return null;
            }
        }
        if (!PluginsController.hasSendMessageHooks()) {
            return params;
        }
        try {
            HookResult r = PluginsController.getInstance().dispatchSendMessage(account, params);
            if (r != null) {
                if (r.isCancel()) {
                    return null;
                }
                if (r.isModify() && r.params instanceof SendMessagesHelper.SendMessageParams) {
                    return (SendMessagesHelper.SendMessageParams) r.params;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return params;
    }

    private static int confirmationText(SendMessagesHelper.SendMessageParams params) {
        TLRPC.Document doc = params.document;
        if (doc == null) {
            return 0;
        }
        if (SolegramConfig.confirmStickers) {
            if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true) || MessageObject.isVideoStickerDocument(doc)) {
                return org.telegram.messenger.R.string.SG_ConfirmSendSticker;
            }
        }
        if (SolegramConfig.confirmGifs && MessageObject.isGifDocument(doc)) {
            return org.telegram.messenger.R.string.SG_ConfirmSendGif;
        }
        if (SolegramConfig.confirmVoice) {
            if (MessageObject.isVoiceDocument(doc)) {
                return org.telegram.messenger.R.string.SG_ConfirmSendVoice;
            }
            if (MessageObject.isRoundVideoDocument(doc)) {
                return org.telegram.messenger.R.string.SG_ConfirmSendVideoMessage;
            }
        }
        return 0;
    }

    private static boolean needsConfirmation(SendMessagesHelper.SendMessageParams params) {
        return (SolegramConfig.confirmStickers || SolegramConfig.confirmGifs || SolegramConfig.confirmVoice) && confirmationText(params) != 0;
    }

    /** @return true when a confirmation dialog was shown (the send is postponed). */
    private static boolean askToSend(int account, SendMessagesHelper.SendMessageParams params) {
        final int text = confirmationText(params);
        org.telegram.ui.ActionBar.BaseFragment fragment = org.telegram.ui.LaunchActivity.getSafeLastFragment();
        if (text == 0 || fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            builder.setTitle(org.telegram.messenger.LocaleController.getString(text));
            builder.setPositiveButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Send), (d, w) -> {
                confirmed.add(params);
                SendMessagesHelper.getInstance(account).sendMessage(params);
            });
            builder.setNegativeButton(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Cancel), null);
            fragment.showDialog(builder.create());
        });
        return true;
    }

    /** Text appended next to the time of a message ("deleted", message id). */
    public static boolean isMessageDeleted(int account, MessageObject messageObject) {
        return SolegramConfig.saveDeletedMessages && MessageVault.isDeleted(account, messageObject);
    }
    // endregion
}
