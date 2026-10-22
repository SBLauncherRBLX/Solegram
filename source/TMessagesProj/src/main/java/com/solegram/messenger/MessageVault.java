/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local archive for messages deleted by other people and for previous versions of edited messages.
 * Everything stays on the device in solegram_vault.db.
 */
public final class MessageVault {

    public static final class EditVersion {
        public final int date;
        public final String text;

        EditVersion(int date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private static final DispatchQueue queue = new DispatchQueue("solegramVault");
    private static volatile Helper helper;
    /** key(account, scopeDialogId) -> deleted message ids. scopeDialogId is 0 for non-channel messages. */
    private static final ConcurrentHashMap<String, HashSet<Integer>> deleted = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> hasEdits = new ConcurrentHashMap<>();
    private static volatile boolean cacheLoaded;

    private MessageVault() {
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper() {
            super(ApplicationLoader.applicationContext, "solegram_vault.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE deleted(account INTEGER, scope INTEGER, mid INTEGER, date INTEGER, PRIMARY KEY(account, scope, mid))");
            db.execSQL("CREATE TABLE edits(account INTEGER, scope INTEGER, mid INTEGER, version INTEGER, text TEXT, PRIMARY KEY(account, scope, mid, version))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    private static SQLiteDatabase db() {
        if (helper == null) {
            synchronized (MessageVault.class) {
                if (helper == null) {
                    helper = new Helper();
                }
            }
        }
        return helper.getWritableDatabase();
    }

    private static String key(int account, long scope) {
        return account + ":" + scope;
    }

    private static String key(int account, long scope, int mid) {
        return account + ":" + scope + ":" + mid;
    }

    /** Messages outside channels have account-wide ids, so they are stored without a dialog. */
    public static long scopeOf(TLRPC.Message message) {
        if (message != null && message.peer_id != null && message.peer_id.channel_id != 0) {
            return -message.peer_id.channel_id;
        }
        return 0;
    }

    public static void init() {
        queue.postRunnable(() -> {
            if (cacheLoaded) {
                return;
            }
            try (Cursor c = db().rawQuery("SELECT account, scope, mid FROM deleted", null)) {
                while (c.moveToNext()) {
                    String k = key(c.getInt(0), c.getLong(1));
                    HashSet<Integer> set = deleted.get(k);
                    if (set == null) {
                        set = new HashSet<>();
                        deleted.put(k, set);
                    }
                    set.add(c.getInt(2));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try (Cursor c = db().rawQuery("SELECT DISTINCT account, scope, mid FROM edits", null)) {
                while (c.moveToNext()) {
                    hasEdits.put(key(c.getInt(0), c.getLong(1), c.getInt(2)), Boolean.TRUE);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            cacheLoaded = true;
        });
    }

    // region Deleted messages

    public static boolean isDeleted(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0) {
            return false;
        }
        HashSet<Integer> set = deleted.get(key(account, scopeOf(messageObject.messageOwner)));
        if (set == null) {
            return false;
        }
        synchronized (set) {
            return set.contains(messageObject.getId());
        }
    }

    /** Remembers ids that the server told us to delete; the messages themselves stay in the local cache. */
    public static void markDeleted(int account, long scope, List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String k = key(account, scope);
        HashSet<Integer> set = deleted.get(k);
        if (set == null) {
            set = new HashSet<>();
            HashSet<Integer> prev = deleted.putIfAbsent(k, set);
            if (prev != null) {
                set = prev;
            }
        }
        synchronized (set) {
            set.addAll(ids);
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        final int date = (int) (System.currentTimeMillis() / 1000);
        queue.postRunnable(() -> {
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                for (Integer mid : copy) {
                    ContentValues v = new ContentValues();
                    v.put("account", account);
                    v.put("scope", scope);
                    v.put("mid", mid);
                    v.put("date", date);
                    db.insertWithOnConflict("deleted", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                db.endTransaction();
            }
        });
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.solegramMessagesMarkedDeleted, scope, copy));
    }

    /** Actually removes a kept message (user chose "delete" on a message that was deleted by others). */
    public static void forget(int account, long scope, List<Integer> ids) {
        HashSet<Integer> set = deleted.get(key(account, scope));
        if (set != null) {
            synchronized (set) {
                set.removeAll(ids);
            }
        }
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        queue.postRunnable(() -> {
            try {
                for (Integer mid : copy) {
                    db().delete("deleted", "account = ? AND scope = ? AND mid = ?", new String[]{String.valueOf(account), String.valueOf(scope), String.valueOf(mid)});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static int deletedCount() {
        int count = 0;
        for (HashSet<Integer> set : deleted.values()) {
            synchronized (set) {
                count += set.size();
            }
        }
        return count;
    }
    // endregion

    // region Edit history

    public static boolean hasEditHistory(int account, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        return hasEdits.containsKey(key(account, scopeOf(messageObject.messageOwner), messageObject.getId()));
    }

    /**
     * Called before an edit update is applied: reads the stored (old) version of the message on the
     * storage queue. The update's own DB write is queued later on the same FIFO queue, so we always
     * see the previous text.
     */
    public static void captureBeforeEdit(int account, TLRPC.Message edited) {
        if (edited == null || edited.peer_id == null) {
            return;
        }
        final long dialogId = org.telegram.messenger.MessageObject.getDialogId(edited);
        final int mid = edited.id;
        final long scope = scopeOf(edited);
        final String newText = edited.message;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.Message old = null;
            org.telegram.SQLite.SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + mid + " LIMIT 1");
                if (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data != null) {
                        old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        data.reuse();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            if (old == null || old.message == null || TextUtils.equals(old.message, newText)) {
                return;
            }
            final int version = old.edit_date != 0 ? old.edit_date : old.date;
            final String oldText = old.message;
            hasEdits.put(key(account, scope, mid), Boolean.TRUE);
            queue.postRunnable(() -> {
                try {
                    ContentValues v = new ContentValues();
                    v.put("account", account);
                    v.put("scope", scope);
                    v.put("mid", mid);
                    v.put("version", version);
                    v.put("text", oldText);
                    db().insertWithOnConflict("edits", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        });
    }

    public interface Callback<T> {
        void run(T result);
    }

    public static void loadEditHistory(int account, MessageObject messageObject, Callback<ArrayList<EditVersion>> callback) {
        final long scope = scopeOf(messageObject.messageOwner);
        final int mid = messageObject.getId();
        queue.postRunnable(() -> {
            ArrayList<EditVersion> result = new ArrayList<>();
            try (Cursor c = db().rawQuery("SELECT version, text FROM edits WHERE account = ? AND scope = ? AND mid = ? ORDER BY version ASC",
                new String[]{String.valueOf(account), String.valueOf(scope), String.valueOf(mid)})) {
                while (c.moveToNext()) {
                    result.add(new EditVersion(c.getInt(0), c.getString(1)));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }
    // endregion

    public static void clearAll(Runnable done) {
        deleted.clear();
        hasEdits.clear();
        queue.postRunnable(() -> {
            try {
                db().execSQL("DELETE FROM deleted");
                db().execSQL("DELETE FROM edits");
                db().execSQL("VACUUM");
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public static void clearAccount(int account) {
        for (String k : new ArrayList<>(deleted.keySet())) {
            if (k.startsWith(account + ":")) {
                deleted.remove(k);
            }
        }
        queue.postRunnable(() -> {
            try {
                db().delete("deleted", "account = ?", new String[]{String.valueOf(account)});
                db().delete("edits", "account = ?", new String[]{String.valueOf(account)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    static boolean isAccountActive(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated();
    }
}
