/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Playlists of music from the user's chats. A playlist keeps where each track is (the chat and the message) with
 * its title, so it lists at once; the tracks themselves are read from Telegram when it plays. Kept on this device.
 */
public final class SolegramPlaylists {

    public static final class Track {
        public long dialogId;
        public int messageId;
        public String title;
        public String artist;
        public int seconds;

        public boolean same(MessageObject m) {
            return m != null && m.getDialogId() == dialogId && m.getId() == messageId;
        }
    }

    public static final class Playlist {
        public long id;
        public String name;
        public final ArrayList<Track> tracks = new ArrayList<>();

        public int seconds() {
            int s = 0;
            for (Track t : tracks) {
                s += t.seconds;
            }
            return s;
        }
    }

    private static final HashMap<Integer, ArrayList<Playlist>> lists = new HashMap<>();

    private SolegramPlaylists() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_playlists", Context.MODE_PRIVATE);
    }

    /** The playlists of an account, newest first. */
    public static ArrayList<Playlist> all(int account) {
        ArrayList<Playlist> l = lists.get(account);
        if (l == null) {
            l = new ArrayList<>();
            try {
                final JSONArray arr = new JSONArray(prefs().getString("a" + account, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject o = arr.getJSONObject(i);
                    final Playlist p = new Playlist();
                    p.id = o.optLong("id");
                    p.name = o.optString("n");
                    final JSONArray tr = o.optJSONArray("t");
                    for (int k = 0; tr != null && k < tr.length(); k++) {
                        final JSONObject to = tr.getJSONObject(k);
                        final Track t = new Track();
                        t.dialogId = to.optLong("d");
                        t.messageId = to.optInt("m");
                        t.title = to.optString("ti");
                        t.artist = to.optString("ar");
                        t.seconds = to.optInt("s");
                        p.tracks.add(t);
                    }
                    l.add(p);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            lists.put(account, l);
        }
        return l;
    }

    private static void save(int account) {
        try {
            final JSONArray arr = new JSONArray();
            for (Playlist p : all(account)) {
                final JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("n", p.name);
                final JSONArray tr = new JSONArray();
                for (Track t : p.tracks) {
                    final JSONObject to = new JSONObject();
                    to.put("d", t.dialogId);
                    to.put("m", t.messageId);
                    to.put("ti", t.title);
                    to.put("ar", t.artist);
                    to.put("s", t.seconds);
                    tr.put(to);
                }
                o.put("t", tr);
                arr.put(o);
            }
            prefs().edit().putString("a" + account, arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Nullable
    public static Playlist get(int account, long id) {
        for (Playlist p : all(account)) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    public static Playlist create(int account, String name) {
        final Playlist p = new Playlist();
        p.id = System.currentTimeMillis();
        p.name = TextUtils.isEmpty(name) ? "Playlist" : name.trim();
        all(account).add(0, p);
        save(account);
        return p;
    }

    public static void rename(int account, Playlist p, String name) {
        if (!TextUtils.isEmpty(name)) {
            p.name = name.trim();
            save(account);
        }
    }

    public static void delete(int account, Playlist p) {
        all(account).remove(p);
        save(account);
        coverFile(p.id).delete();
        covers.remove(p.id);
    }

    // ------------------------------------------------------------------ covers: a square picture kept in the app's files

    private static final android.util.LruCache<Long, android.graphics.Bitmap> covers = new android.util.LruCache<>(16);

    private static java.io.File coverFile(long id) {
        final java.io.File dir = new java.io.File(ApplicationLoader.applicationContext.getFilesDir(), "playlist_covers");
        dir.mkdirs();
        return new java.io.File(dir, id + ".jpg");
    }

    /** The playlist's cover, or null when it has none. */
    @Nullable
    public static android.graphics.Bitmap cover(Playlist p) {
        android.graphics.Bitmap b = covers.get(p.id);
        if (b == null) {
            final java.io.File f = coverFile(p.id);
            if (f.exists()) {
                b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b != null) {
                    covers.put(p.id, b);
                }
            }
        }
        return b;
    }

    /** Makes a picture the playlist's cover: cut to a square in the middle, 640 px. False when it can't be read. */
    public static boolean setCover(Playlist p, android.net.Uri uri) {
        try {
            final android.content.ContentResolver cr = ApplicationLoader.applicationContext.getContentResolver();
            final android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = cr.openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false;
            }
            final android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (o.inSampleSize * 2) >= 640) {
                o.inSampleSize *= 2;
            }
            android.graphics.Bitmap src;
            try (java.io.InputStream in = cr.openInputStream(uri)) {
                src = android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (src == null) {
                return false;
            }
            final int side = Math.min(src.getWidth(), src.getHeight());
            final android.graphics.Bitmap square = android.graphics.Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            final android.graphics.Bitmap out = side > 640 ? android.graphics.Bitmap.createScaledBitmap(square, 640, 640, true) : square;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(coverFile(p.id))) {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fos);
            }
            covers.put(p.id, out);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void removeCover(Playlist p) {
        coverFile(p.id).delete();
        covers.remove(p.id);
    }

    /** Adds a track (once); false when it is already there or is not music. */
    public static boolean add(int account, Playlist p, MessageObject m) {
        if (m == null || !m.isMusic()) {
            return false;
        }
        for (Track t : p.tracks) {
            if (t.same(m)) {
                return false;
            }
        }
        final Track t = new Track();
        t.dialogId = m.getDialogId();
        t.messageId = m.getId();
        t.title = m.getMusicTitle();
        t.artist = m.getMusicAuthor();
        t.seconds = (int) Math.round(m.getDuration());
        p.tracks.add(t);
        save(account);
        return true;
    }

    public static void remove(int account, Playlist p, int index) {
        if (index >= 0 && index < p.tracks.size()) {
            p.tracks.remove(index);
            save(account);
        }
    }

    public static void move(int account, Playlist p, int from, int to) {
        if (from >= 0 && to >= 0 && from < p.tracks.size() && to < p.tracks.size()) {
            p.tracks.add(to, p.tracks.remove(from));
            save(account);
        }
    }

    public static boolean contains(int account, MessageObject m) {
        for (Playlist p : all(account)) {
            for (Track t : p.tracks) {
                if (t.same(m)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Reads the playlist's messages from Telegram, in its order (deleted ones are left out). UI thread. */
    public static void load(int account, Playlist p, Utilities.Callback<ArrayList<MessageObject>> done) {
        final MessagesController mc = MessagesController.getInstance(account);
        final LinkedHashMap<Long, ArrayList<Integer>> byDialog = new LinkedHashMap<>();
        for (Track t : p.tracks) {
            ArrayList<Integer> ids = byDialog.get(t.dialogId);
            if (ids == null) {
                ids = new ArrayList<>();
                byDialog.put(t.dialogId, ids);
            }
            ids.add(t.messageId);
        }
        if (byDialog.isEmpty()) {
            done.run(new ArrayList<>());
            return;
        }
        final HashMap<String, MessageObject> found = new HashMap<>();
        final int[] left = {byDialog.size()};
        for (java.util.Map.Entry<Long, ArrayList<Integer>> e : byDialog.entrySet()) {
            final long dialogId = e.getKey();
            final TLObject req;
            final TLRPC.Chat chat = DialogObject.isChatDialog(dialogId) ? mc.getChat(-dialogId) : null;
            if (chat != null && ChatObject.isChannel(chat)) {
                final TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
                r.channel = mc.getInputChannel(chat);
                r.id.addAll(e.getValue());
                req = r;
            } else {
                final TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
                r.id.addAll(e.getValue());
                req = r;
            }
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                    for (TLRPC.Message m : res.messages) {
                        if (m == null || m instanceof TLRPC.TL_messageEmpty || m.media == null) {
                            continue;
                        }
                        final long did = MessageObject.getDialogId(m);
                        final MessageObject mo = new MessageObject(account, m, false, true);
                        if (mo.isMusic()) {
                            found.put((did != 0 ? did : dialogId) + "_" + m.id, mo);
                        }
                    }
                }
                if (--left[0] == 0) {
                    final ArrayList<MessageObject> out = new ArrayList<>();
                    for (Track t : p.tracks) {
                        final MessageObject mo = found.get(t.dialogId + "_" + t.messageId);
                        if (mo != null) {
                            out.add(mo);
                        }
                    }
                    done.run(out);
                }
            }));
        }
    }
}
