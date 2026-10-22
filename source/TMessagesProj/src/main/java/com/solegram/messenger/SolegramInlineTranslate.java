/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translating one message right in its bubble, from the message menu ("Translate here"); the menu
 * then offers the original back. Works without Premium (Solegram translator); kept while the app runs.
 * The bubble text is read from here by MessageObject (also on the loader thread), hence the concurrent maps.
 */
public final class SolegramInlineTranslate {

    private static final class Entry {
        final String source;
        final String text;

        Entry(String source, String text) {
            this.source = source;
            this.text = text;
        }
    }

    private static final ConcurrentHashMap<String, Entry> translations = new ConcurrentHashMap<>();
    private static final Set<String> shown = ConcurrentHashMap.newKeySet();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();

    private SolegramInlineTranslate() {
    }

    private static String key(MessageObject message) {
        return message.currentAccount + "_" + message.getDialogId() + "_" + message.getId() + "_" + TranslateAlert2.getToLanguage();
    }

    /** In an album the text lives in one of the items (the caption); the menu may be opened on any of them. */
    public static MessageObject target(MessageObject message, MessageObject.GroupedMessages group) {
        if (group != null && group.messages != null && (message == null || message.messageOwner == null || TextUtils.isEmpty(message.messageOwner.message))) {
            final ArrayList<MessageObject> messages = group.messages;
            for (int i = 0; i < messages.size(); i++) {
                final MessageObject m = messages.get(i);
                if (m != null && m.messageOwner != null && !TextUtils.isEmpty(m.messageOwner.message)) {
                    return m;
                }
            }
        }
        return message;
    }

    public static boolean canTranslate(MessageObject message) {
        return message != null && message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message)
            && message.getId() > 0 && !message.isOutOwner() && message.type != MessageObject.TYPE_STORY;
    }

    /** The translation of this message is on its way (its bubble shows that the AI is at work). */
    public static boolean isLoading(MessageObject message) {
        return message != null && message.messageOwner != null && !loading.isEmpty() && loading.contains(key(message));
    }

    public static boolean isShown(MessageObject message) {
        return message != null && !shown.isEmpty() && shown.contains(key(message));
    }

    /** The translation to show in the bubble instead of the original, or null. */
    public static CharSequence shownText(MessageObject message) {
        if (message == null || message.messageOwner == null || shown.isEmpty() || !SolegramConfig.translateHere) {
            return null;
        }
        final String key = key(message);
        if (!shown.contains(key)) {
            return null;
        }
        final Entry entry = translations.get(key);
        if (entry == null) {
            return null;
        }
        if (!TextUtils.equals(entry.source, message.messageOwner.message)) {
            // the message was edited: its translation is stale, the original comes back
            shown.remove(key);
            translations.remove(key, entry);
            return null;
        }
        return entry.text;
    }

    /** Drops the in-place translation of this message (a summary is about to take the bubble). */
    public static void hide(MessageObject message) {
        if (message != null && !shown.isEmpty()) {
            shown.remove(key(message));
        }
    }

    public static void toggle(ChatActivity chat, MessageObject message) {
        if (!canTranslate(message)) {
            return;
        }
        final String key = key(message);
        if (shown.remove(key)) {
            refresh(message);
            return;
        }
        if (message.messageOwner.summarizedOpen) {
            // the translation replaces an open AI summary: the bubble shows the whole message again
            message.messageOwner.summarizedOpen = false;
            org.telegram.messenger.MessagesStorage.getInstance(message.currentAccount).updateMessageCustomParams(message.getDialogId(), message.messageOwner);
            refresh(message); // the original right away, whatever happens to the translation
        }
        final String source = message.messageOwner.message;
        final Entry cached = translations.get(key);
        if (cached != null && TextUtils.equals(cached.source, source)) {
            shown.add(key);
            refresh(message);
            return;
        }
        if (!loading.add(key)) {
            return;
        }
        // the bubble shows that the translation is on its way
        NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.messageTranslating, message);
        SolegramTranslator.translate(source, TranslateAlert2.getToLanguage(), result -> {
            loading.remove(key);
            if (result == null) {
                NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.messageTranslating, message);
                if (chat != null && chat.getParentActivity() != null) {
                    BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.TranslationFailedAlert2)).show();
                }
                return;
            }
            translations.put(key, new Entry(source, result));
            shown.add(key);
            refresh(message);
        });
    }

    /**
     * Asks the open chat to re-bind the bubble: its messageTranslated handler calls updateTranslation, which picks
     * the text from here and reports the change, so the cell is measured again (and the album laid out again).
     */
    private static void refresh(MessageObject message) {
        NotificationCenter.getInstance(message.currentAccount).postNotificationName(NotificationCenter.messageTranslated, message);
    }
}
