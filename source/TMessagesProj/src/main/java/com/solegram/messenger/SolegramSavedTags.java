/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Tags for Saved Messages without Premium, kept on this device: an emoji put on a saved message from its menu; the
 * tags screen lists the tags and the messages with each, newest first, and opens a message in Saved Messages.
 * A short preview of the message is kept with its tags so the list shows without loading the chat.
 */
public final class SolegramSavedTags {

    /** The emoji offered for a tag. */
    public static final String[] EMOJI = {
        "❤", "👍", "🔥", "⭐", "📌", "✅", "❗", "💡",
        "📚", "🎵", "🎬", "📷", "🛒", "💼", "🏠", "✈",
        "🍔", "💰", "🎁", "📝", "🔗", "🧠", "😂", "🤔"
    };

    public static final class Entry {
        public final int messageId;
        public final ArrayList<String> tags;
        public final String preview;
        public final int date;

        Entry(int messageId, ArrayList<String> tags, String preview, int date) {
            this.messageId = messageId;
            this.tags = tags;
            this.preview = preview;
            this.date = date;
        }
    }

    private SolegramSavedTags() {
    }

    private static String key(int account) {
        return "savedTags_" + account;
    }

    private static synchronized LinkedHashMap<Integer, Entry> load(int account) {
        final LinkedHashMap<Integer, Entry> map = new LinkedHashMap<>();
        try {
            final JSONArray array = new JSONArray(SolegramConfig.prefs().getString(key(account), "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                final ArrayList<String> tags = new ArrayList<>();
                final JSONArray t = o.getJSONArray("t");
                for (int j = 0; j < t.length(); j++) {
                    tags.add(t.getString(j));
                }
                map.put(o.getInt("i"), new Entry(o.getInt("i"), tags, o.optString("x", ""), o.optInt("d", 0)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return map;
    }

    private static synchronized void save(int account, LinkedHashMap<Integer, Entry> map) {
        try {
            final JSONArray array = new JSONArray();
            for (Entry e : map.values()) {
                if (e.tags.isEmpty()) {
                    continue;
                }
                final JSONObject o = new JSONObject();
                o.put("i", e.messageId);
                o.put("t", new JSONArray(e.tags));
                o.put("x", e.preview);
                o.put("d", e.date);
                array.put(o);
            }
            SolegramConfig.prefs().edit().putString(key(account), array.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static ArrayList<String> tagsOf(int account, int messageId) {
        final Entry e = load(account).get(messageId);
        return e != null ? new ArrayList<>(e.tags) : new ArrayList<>();
    }

    /** Puts the tag on the message, or takes it off when it is there. */
    public static synchronized void toggle(int account, MessageObject message, String tag) {
        if (message == null || TextUtils.isEmpty(tag)) {
            return;
        }
        final LinkedHashMap<Integer, Entry> map = load(account);
        Entry e = map.get(message.getId());
        if (e == null) {
            e = new Entry(message.getId(), new ArrayList<>(), preview(message), message.messageOwner != null ? message.messageOwner.date : 0);
            map.put(message.getId(), e);
        }
        if (!e.tags.remove(tag)) {
            e.tags.add(tag);
        }
        save(account, map);
    }

    private static String preview(MessageObject message) {
        CharSequence text = message.caption != null && message.caption.length() > 0 ? message.caption : message.messageText;
        String s = text == null ? "" : text.toString().replace('\n', ' ').trim();
        if (s.length() > 140) {
            s = s.substring(0, 140) + "…";
        }
        return s;
    }

    /** Every tag with how many messages carry it, the most used first. */
    public static ArrayList<String[]> tags(int account) {
        final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Entry e : load(account).values()) {
            for (String t : e.tags) {
                final Integer c = counts.get(t);
                counts.put(t, c == null ? 1 : c + 1);
            }
        }
        final ArrayList<String[]> list = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> c : counts.entrySet()) {
            list.add(new String[]{c.getKey(), String.valueOf(c.getValue())});
        }
        java.util.Collections.sort(list, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return list;
    }

    /** The messages with a tag (all tagged messages for null), newest first. */
    public static ArrayList<Entry> messages(int account, String tag) {
        final ArrayList<Entry> list = new ArrayList<>();
        for (Entry e : load(account).values()) {
            if (tag == null ? !e.tags.isEmpty() : e.tags.contains(tag)) {
                list.add(e);
            }
        }
        java.util.Collections.sort(list, (a, b) -> b.date != a.date ? Integer.compare(b.date, a.date) : Integer.compare(b.messageId, a.messageId));
        return list;
    }

    /** A saved message was deleted: its tags go too. */
    public static synchronized void forget(int account, int messageId) {
        final LinkedHashMap<Integer, Entry> map = load(account);
        if (map.remove(messageId) != null) {
            save(account, map);
        }
    }
}
