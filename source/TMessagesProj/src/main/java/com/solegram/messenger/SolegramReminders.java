/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AtomicFile;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reminders made from chat messages: what to remember (composed by AI from the messages, or taken from them),
 * when, and where to go back to. Kept in one small file for all accounts; one alarm is set for the nearest one,
 * exact when Android allows it, otherwise in a few steps that never overshoot by more than minutes.
 */
public final class SolegramReminders {

    public static final int REPEAT_NONE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_WEEKLY = 2;

    /** Waiting for its time. */
    public static final int STATE_PENDING = 0;
    /** Shown, not marked done yet (a one-time reminder). */
    public static final int STATE_FIRED = 1;
    public static final int STATE_DONE = 2;

    static final String CHANNEL_ID = "solegram_reminders";
    /** Notifications go under their own tag: their ids can never meet Telegram's (which use plain ids). */
    static final String NOTIFICATION_TAG = "solegram_reminder";
    static final String ACTION_ALARM = "com.solegram.messenger.REMINDER_ALARM";
    static final String ACTION_SNOOZE = "com.solegram.messenger.REMINDER_SNOOZE";
    static final String ACTION_DONE = "com.solegram.messenger.REMINDER_DONE";
    public static final String EXTRA_ID = "solegram_reminder";

    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    /** Reminders this close to a due one come with it: one wake-up for reminders a moment apart. */
    private static final long SLACK = MINUTE;
    /** Without exact alarms the last step is set this close: Android then delivers it at most ~3/4 of it late. */
    private static final long LAST_STEP = 8 * MINUTE;
    private static final int MAX_DONE = 50;
    public static final int TITLE_MAX = 60;

    public static final class Reminder {
        public int id;
        public int account;
        /** The account's user: account slots can change hands after a logout. */
        public long userId;
        public long dialogId;
        /** Forum topic, 0 = none. */
        public long topicId;
        public int[] messageIds = new int[0];
        public String chatName = "";
        public String title = "";
        public String body = "";
        public long due;
        /** Wall-clock time of a repeating reminder (minutes after midnight): kept across daylight saving changes. */
        public int wall = -1;
        public int repeat;
        public long created;
        public int state;
        /** A repeating reminder put off for a while (its series stays as it was), 0 = none. */
        public long snoozeAt;
        public long firedAt;
        public long doneAt;
        /** SolegramSummarizer.SOURCE_*, or 0 when the text was taken from the messages or typed. */
        public int source;

        /** When it comes next, 0 = never (done, or shown and waiting to be marked done). */
        public long nextTime() {
            if (state == STATE_DONE) {
                return 0;
            }
            long t = state == STATE_PENDING ? due : 0;
            if (snoozeAt > 0 && (t == 0 || snoozeAt < t)) {
                t = snoozeAt;
            }
            return t;
        }

        public int messageId() {
            return messageIds.length > 0 ? messageIds[0] : 0;
        }

        public boolean isOverdue() {
            return state == STATE_FIRED && snoozeAt == 0;
        }

        Reminder copy() {
            final Reminder r = new Reminder();
            r.id = id;
            r.account = account;
            r.userId = userId;
            r.dialogId = dialogId;
            r.topicId = topicId;
            r.messageIds = messageIds.clone();
            r.chatName = chatName;
            r.title = title;
            r.body = body;
            r.due = due;
            r.wall = wall;
            r.repeat = repeat;
            r.created = created;
            r.state = state;
            r.snoozeAt = snoozeAt;
            r.firedAt = firedAt;
            r.doneAt = doneAt;
            r.source = source;
            return r;
        }

        JSONObject toJson() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("id", id).put("account", account).put("user", userId).put("dialog", dialogId).put("topic", topicId);
            final JSONArray ids = new JSONArray();
            for (int m : messageIds) {
                ids.put(m);
            }
            o.put("messages", ids).put("chat", chatName).put("title", title).put("body", body).put("due", due).put("wall", wall)
                .put("repeat", repeat).put("created", created).put("state", state).put("snooze", snoozeAt).put("fired", firedAt)
                .put("done", doneAt).put("source", source);
            return o;
        }

        static Reminder fromJson(JSONObject o) {
            final Reminder r = new Reminder();
            r.id = o.optInt("id");
            r.account = o.optInt("account");
            r.userId = o.optLong("user");
            r.dialogId = o.optLong("dialog");
            r.topicId = o.optLong("topic");
            final JSONArray ids = o.optJSONArray("messages");
            if (ids != null) {
                r.messageIds = new int[ids.length()];
                for (int i = 0; i < ids.length(); i++) {
                    r.messageIds[i] = ids.optInt(i);
                }
            }
            r.chatName = o.optString("chat", "");
            r.title = o.optString("title", "");
            r.body = o.optString("body", "");
            r.due = o.optLong("due");
            r.wall = o.optInt("wall", -1);
            r.repeat = o.optInt("repeat");
            r.created = o.optLong("created");
            r.state = o.optInt("state");
            r.snoozeAt = o.optLong("snooze");
            r.firedAt = o.optLong("fired");
            r.doneAt = o.optLong("done");
            r.source = o.optInt("source");
            return r;
        }
    }

    private static final Object lock = new Object();
    private static ArrayList<Reminder> items;
    private static int nextId = 1;
    // file writes, alarms and notifications, in order
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();

    private SolegramReminders() {
    }

    // region Storage

    private static File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), "solegram_reminders.json");
    }

    private static void ensureLoaded() {
        if (items != null) {
            return;
        }
        items = new ArrayList<>();
        try {
            final File f = file();
            if (!f.exists()) {
                return;
            }
            final JSONObject root = new JSONObject(new String(new AtomicFile(f).readFully(), StandardCharsets.UTF_8));
            nextId = Math.max(1, root.optInt("next", 1));
            final JSONArray list = root.optJSONArray("items");
            for (int i = 0; list != null && i < list.length(); i++) {
                final Reminder r = Reminder.fromJson(list.getJSONObject(i));
                if (r.id > 0) {
                    items.add(r);
                    nextId = Math.max(nextId, r.id + 1);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Writes a snapshot (lock held) on the queue. */
    private static void saveLocked() {
        final String json;
        try {
            final JSONObject root = new JSONObject();
            root.put("version", 1).put("next", nextId);
            final JSONArray list = new JSONArray();
            for (Reminder r : items) {
                list.put(r.toJson());
            }
            root.put("items", list);
            json = root.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        queue.execute(() -> {
            final AtomicFile f = new AtomicFile(file());
            FileOutputStream out = null;
            try {
                out = f.startWrite();
                out.write(json.getBytes(StandardCharsets.UTF_8));
                f.finishWrite(out);
            } catch (Throwable e) {
                if (out != null) {
                    f.failWrite(out);
                }
                FileLog.e(e);
            }
        });
    }

    /** Only the newest done ones stay for the list. Lock held. */
    private static void pruneLocked() {
        int done = 0;
        for (Reminder r : items) {
            if (r.state == STATE_DONE) {
                done++;
            }
        }
        if (done <= MAX_DONE) {
            return;
        }
        final ArrayList<Reminder> doneList = new ArrayList<>();
        for (Reminder r : items) {
            if (r.state == STATE_DONE) {
                doneList.add(r);
            }
        }
        Collections.sort(doneList, (a, b) -> Long.compare(a.doneAt, b.doneAt));
        for (int i = 0; i < done - MAX_DONE; i++) {
            items.remove(doneList.get(i));
        }
    }

    private static void changed() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramRemindersChanged));
    }

    /** Waits until queued writes are on disk: a receiver's process may end right after it returns. Not the UI thread. */
    static void flush() {
        try {
            queue.submit(() -> {
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignore) {
        }
    }

    /** At app start: the file is read and the alarm set again (it is lost on a force stop or an app update). */
    public static void warmUp() {
        queue.execute(() -> {
            synchronized (lock) {
                ensureLoaded();
            }
            deliverDue();
            scheduleNow();
        });
    }

    /** All reminders (copies), soonest first; done ones last, newest first. */
    public static ArrayList<Reminder> list() {
        final ArrayList<Reminder> out = new ArrayList<>();
        synchronized (lock) {
            ensureLoaded();
            for (Reminder r : items) {
                out.add(r.copy());
            }
        }
        Collections.sort(out, (a, b) -> {
            final boolean ad = a.state == STATE_DONE, bd = b.state == STATE_DONE;
            if (ad != bd) {
                return ad ? 1 : -1;
            }
            if (ad) {
                return Long.compare(b.doneAt, a.doneAt);
            }
            return Long.compare(sortTime(a), sortTime(b));
        });
        return out;
    }

    private static long sortTime(Reminder r) {
        final long t = r.nextTime();
        return t != 0 ? t : r.firedAt;
    }

    /** Reminders still to come (for the settings tile). */
    public static int upcomingCount() {
        int n = 0;
        synchronized (lock) {
            ensureLoaded();
            for (Reminder r : items) {
                if (r.state != STATE_DONE) {
                    n++;
                }
            }
        }
        return n;
    }

    @Nullable
    public static Reminder get(int id) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            return r != null ? r.copy() : null;
        }
    }

    private static Reminder find(int id) {
        for (Reminder r : items) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }
    // endregion

    // region Changes

    public static Reminder create(int account, long dialogId, long topicId, int[] messageIds, String chatName,
                                  String title, String body, int source, long due, int repeat) {
        final Reminder r = new Reminder();
        r.account = account;
        r.userId = UserConfig.getInstance(account).getClientUserId();
        r.dialogId = dialogId;
        r.topicId = topicId;
        r.messageIds = messageIds != null ? messageIds : new int[0];
        r.chatName = chatName != null ? chatName : "";
        r.title = title;
        r.body = body != null ? body : "";
        r.source = source;
        r.repeat = repeat;
        r.created = System.currentTimeMillis();
        setDue(r, due);
        synchronized (lock) {
            ensureLoaded();
            r.id = nextId++;
            items.add(r);
            saveLocked();
        }
        schedule();
        changed();
        return r.copy();
    }

    private static void setDue(Reminder r, long due) {
        r.due = due;
        r.state = STATE_PENDING;
        r.snoozeAt = 0;
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(due);
        r.wall = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** The text came later (AI finished after "Create") and the user had not typed their own. */
    public static void updateText(int id, String title, String body, int source) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r == null) {
                return;
            }
            r.title = title;
            r.body = body != null ? body : "";
            r.source = source;
            saveLocked();
        }
        changed();
    }

    public static void setTime(int id, long due, int repeat) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r == null) {
                return;
            }
            setDue(r, due);
            r.repeat = repeat;
            r.doneAt = 0;
            saveLocked();
        }
        cancelNotification(id);
        schedule();
        changed();
    }

    /** Marks done (a repeating one stops repeating), or brings a done one back when it still has a time ahead. */
    public static void setDone(int id, boolean done) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r == null) {
                return;
            }
            if (done) {
                r.state = STATE_DONE;
                r.snoozeAt = 0;
                r.doneAt = System.currentTimeMillis();
                pruneLocked();
            } else {
                r.state = r.due > System.currentTimeMillis() ? STATE_PENDING : STATE_FIRED;
                r.doneAt = 0;
            }
            saveLocked();
        }
        cancelNotification(id);
        schedule();
        changed();
    }

    public static void delete(int id) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r == null) {
                return;
            }
            items.remove(r);
            saveLocked();
        }
        cancelNotification(id);
        schedule();
        changed();
    }

    public static void clearDone() {
        synchronized (lock) {
            ensureLoaded();
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).state == STATE_DONE) {
                    items.remove(i);
                }
            }
            saveLocked();
        }
        changed();
    }

    /** "Snooze for an hour" from the notification. */
    static void snooze(int id) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r == null) {
                return;
            }
            final long at = roundToMinute(System.currentTimeMillis() + HOUR);
            if (r.repeat == REPEAT_NONE) {
                r.due = at;
                r.state = STATE_PENDING;
            } else {
                r.snoozeAt = at; // the series keeps its own times
            }
            saveLocked();
        }
        cancelNotification(id);
        schedule();
        changed();
    }

    /** "Done" from the notification: a one-time reminder is finished; a repeating one only this time. */
    static void doneFromNotification(int id) {
        synchronized (lock) {
            ensureLoaded();
            final Reminder r = find(id);
            if (r != null) {
                if (r.repeat == REPEAT_NONE) {
                    r.state = STATE_DONE;
                    r.doneAt = System.currentTimeMillis();
                    pruneLocked();
                } else {
                    r.snoozeAt = 0;
                }
                saveLocked();
            }
        }
        cancelNotification(id);
        schedule();
        changed();
    }

    /** Called by LaunchActivity for "open chat" intents: a one-time reminder opened from its notification is done. */
    public static void onNotificationOpened(Intent intent) {
        final int id = intent != null ? intent.getIntExtra(EXTRA_ID, 0) : 0;
        if (id == 0) {
            return;
        }
        intent.removeExtra(EXTRA_ID); // the intent is handled again when the activity is recreated
        final Reminder r = get(id);
        if (r != null && r.repeat == REPEAT_NONE && r.state == STATE_FIRED) {
            setDone(id, true);
        }
    }

    public static long roundToMinute(long ms) {
        return ms / MINUTE * MINUTE;
    }
    // endregion

    // region Alarm

    /** Sets the alarm for the nearest reminder (or removes it). Any thread. */
    public static void schedule() {
        queue.execute(SolegramReminders::scheduleNow);
    }

    public static boolean canScheduleExact() {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        try {
            final AlarmManager am = (AlarmManager) ApplicationLoader.applicationContext.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable e) {
            return false;
        }
    }

    /** The system page where the user allows exact alarms (Android 12+), or null. */
    @Nullable
    public static Intent exactAlarmSettingsIntent() {
        if (Build.VERSION.SDK_INT < 31) {
            return null;
        }
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
    }

    private static PendingIntent alarmIntent(Context context) {
        final Intent intent = new Intent(context, SolegramReminderReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(context, 0x5E1A, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The queue thread. */
    static void scheduleNow() {
        long next = Long.MAX_VALUE;
        synchronized (lock) {
            ensureLoaded();
            for (Reminder r : items) {
                final long t = r.nextTime();
                if (t > 0 && t < next) {
                    next = t;
                }
            }
        }
        final Context context = ApplicationLoader.applicationContext;
        final AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        final PendingIntent pi = alarmIntent(context);
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        final long now = System.currentTimeMillis();
        try {
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(next, now + 1000), pi);
                return;
            }
        } catch (SecurityException e) {
            // the permission was taken back a moment ago: the inexact way below
        }
        // Inexact alarms may come up to 3/4 of their distance late (an hour at most). So the alarm is set at a point
        // that comes before the due time even when late; each wake-up sets the next step, until the last few minutes.
        final long left = next - now;
        final long at = left <= LAST_STEP ? Math.max(next, now + 1000) : now + (long) (left * 0.55);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** The next time of a repeating reminder after {@code now}, at its wall-clock time (daylight saving safe). */
    private static long nextOccurrence(Reminder r, long now) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(r.due);
        for (int i = 0; i < 5000 && c.getTimeInMillis() <= now + SLACK; i++) {
            c.add(r.repeat == REPEAT_WEEKLY ? Calendar.WEEK_OF_YEAR : Calendar.DAY_OF_YEAR, 1);
            if (r.wall >= 0) {
                c.set(Calendar.HOUR_OF_DAY, r.wall / 60);
                c.set(Calendar.MINUTE, r.wall % 60);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
            }
        }
        return c.getTimeInMillis();
    }

    /** Shows what is due (also what was missed while the phone was off). Background threads. */
    static void deliverDue() {
        final long now = System.currentTimeMillis();
        final ArrayList<Reminder> fire = new ArrayList<>();
        synchronized (lock) {
            ensureLoaded();
            for (Reminder r : items) {
                if (r.state == STATE_DONE) {
                    continue;
                }
                boolean show = false;
                if (r.state == STATE_PENDING && r.due <= now + SLACK) {
                    show = true;
                    if (r.repeat != REPEAT_NONE) {
                        r.due = nextOccurrence(r, now);
                    } else {
                        r.state = STATE_FIRED;
                    }
                }
                if (r.snoozeAt > 0 && r.snoozeAt <= now + SLACK) {
                    show = true;
                    r.snoozeAt = 0;
                }
                if (show) {
                    r.firedAt = now;
                    fire.add(r.copy());
                }
            }
            if (!fire.isEmpty()) {
                saveLocked();
            }
        }
        if (fire.isEmpty()) {
            return;
        }
        for (Reminder r : fire) {
            try {
                showNotification(r);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        changed();
    }
    // endregion

    // region Notification

    /** The account the reminder belongs to now, or -1 when it is no longer on this phone. */
    public static int resolveAccount(Reminder r) {
        if (UserConfig.isValidAccount(r.account) && UserConfig.getInstance(r.account).getClientUserId() == r.userId) {
            return r.account;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.isValidAccount(a) && UserConfig.getInstance(a).getClientUserId() == r.userId) {
                return a;
            }
        }
        return -1;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        // sound, vibration and the rest are the user's to change in the system settings of this channel
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.SG_Reminders), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(LocaleController.getString(R.string.SG_RemindersChannelInfo));
        channel.enableVibration(true);
        channel.enableLights(true);
        nm.createNotificationChannel(channel);
    }

    /** The system settings of the reminders channel (sound, vibration), or of the app before Android 8. */
    public static Intent channelSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ensureChannel(context);
            return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }

    static void cancelNotification(int id) {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_TAG, id);
        } catch (Throwable ignore) {
        }
    }

    /** Opens the chat at the message, in the reminder's account (as Telegram's own notifications do). */
    public static Intent openIntent(Reminder r, int account) {
        final Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        intent.setAction("com.tmessages.openchat" + r.id);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        if (DialogObject.isEncryptedDialog(r.dialogId)) {
            intent.putExtra("encId", DialogObject.getEncryptedChatId(r.dialogId));
        } else if (DialogObject.isUserDialog(r.dialogId)) {
            intent.putExtra("userId", r.dialogId);
        } else {
            intent.putExtra("chatId", -r.dialogId);
        }
        if (r.topicId != 0) {
            intent.putExtra("topicId", r.topicId);
        }
        if (r.messageId() > 0) {
            intent.putExtra("message_id", r.messageId());
        }
        intent.putExtra("currentAccount", account);
        intent.putExtra(EXTRA_ID, r.id);
        return intent;
    }

    private static PendingIntent actionIntent(Context context, int id, String action, int code) {
        final Intent intent = new Intent(context, SolegramReminderReceiver.class).setAction(action).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(context, id * 4 + code, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void showNotification(Reminder r) {
        final Context context = ApplicationLoader.applicationContext;
        final int account = resolveAccount(r);
        if (account < 0) {
            // the account was logged out on this phone: its chats are gone from here, so is the reminder
            synchronized (lock) {
                final Reminder own = find(r.id);
                if (own != null) {
                    items.remove(own);
                    saveLocked();
                }
            }
            return;
        }
        ensureChannel(context);
        // with a passcode on the app the content stays hidden, as Telegram does with messages
        final boolean hidden = SharedConfig.passcodeHash != null && SharedConfig.passcodeHash.length() > 0;
        final String from = TextUtils.isEmpty(r.chatName) ? null : LocaleController.formatString(R.string.SG_ReminderFromChat, r.chatName);
        final String text;
        if (hidden) {
            text = LocaleController.getString(R.string.SG_ReminderHidden);
        } else if (TextUtils.isEmpty(r.body)) {
            text = from != null ? from : "";
        } else {
            text = from != null ? r.body + "\n" + from : r.body;
        }
        final String title = hidden ? LocaleController.getString(R.string.SG_ReminderNotificationTitle) : r.title;
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setColor(0xff11acfa)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(r.firedAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setColor(0xff11acfa)
                .setContentTitle(LocaleController.getString(R.string.SG_ReminderNotificationTitle))
                .build())
            .setContentIntent(PendingIntent.getActivity(context, r.id, openIntent(r, account), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.msg_mute_1h, LocaleController.getString(R.string.SG_ReminderSnooze), actionIntent(context, r.id, ACTION_SNOOZE, 1))
            .addAction(R.drawable.msg_select, LocaleController.getString(R.string.SG_ReminderDone), actionIntent(context, r.id, ACTION_DONE, 2));
        if (DialogObject.isEncryptedDialog(r.dialogId)) {
            builder.setLocalOnly(true);
        }
        if (!hidden) {
            final Bitmap avatar = avatar(account, r.dialogId);
            if (avatar != null) {
                builder.setLargeIcon(avatar);
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, r.id, builder.build());
        } catch (SecurityException e) {
            FileLog.e(e); // notifications are not allowed (Android 13+): the list in Solegram's settings still has it
        }
    }

    /** The chat's photo, round, if it is on the phone already. Background threads. */
    @Nullable
    private static Bitmap avatar(int account, long dialogId) {
        try {
            TLObject photo = null;
            long userId = 0;
            if (DialogObject.isEncryptedDialog(dialogId)) {
                final TLRPC.EncryptedChat enc = MessagesController.getInstance(account).getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                userId = enc != null ? enc.user_id : 0;
            } else if (DialogObject.isUserDialog(dialogId)) {
                userId = dialogId;
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
                if (chat == null) {
                    chat = MessagesStorage.getInstance(account).getChatSync(-dialogId);
                }
                if (chat != null && chat.photo != null) {
                    photo = chat.photo.photo_small;
                }
            }
            if (userId != 0) {
                if (userId == UserConfig.getInstance(account).getClientUserId()) {
                    return null; // Saved Messages
                }
                TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
                if (user == null) {
                    user = MessagesStorage.getInstance(account).getUserSync(userId);
                }
                if (user != null && user.photo != null) {
                    photo = user.photo.photo_small;
                }
            }
            if (photo == null) {
                return null;
            }
            final File f = FileLoader.getInstance(account).getPathToAttach(photo, true);
            if (f == null || !f.exists()) {
                return null;
            }
            final Bitmap source = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (source == null) {
                return null;
            }
            final int size = Math.min(source.getWidth(), source.getHeight());
            final Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            final BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            final Matrix m = new Matrix();
            m.setTranslate(-(source.getWidth() - size) / 2f, -(source.getHeight() - size) / 2f);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint);
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
    // endregion

    // region Opening

    /** The chat at the reminder's message: in this screen's account, or through Telegram's own "open chat" intent. */
    public static void open(BaseFragment fragment, Reminder r) {
        final int account = resolveAccount(r);
        if (account < 0 || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (account != fragment.getCurrentAccount()) {
            final Intent intent = openIntent(r, account);
            intent.removeExtra(EXTRA_ID);
            fragment.getParentActivity().startActivity(intent);
            return;
        }
        final Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(r.dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(r.dialogId));
        } else if (DialogObject.isUserDialog(r.dialogId)) {
            args.putLong("user_id", r.dialogId);
        } else {
            args.putLong("chat_id", -r.dialogId);
        }
        final int messageId = r.messageId();
        if (messageId > 0 && !DialogObject.isEncryptedDialog(r.dialogId)) {
            args.putInt("message_id", messageId);
        }
        if (r.topicId != 0 && r.dialogId < 0) {
            final TLRPC.TL_forumTopic topic = fragment.getMessagesController().getTopicsController().findTopic(-r.dialogId, r.topicId);
            if (topic != null) {
                org.telegram.ui.Components.Forum.ForumUtilities.openTopic(fragment, -r.dialogId, topic, Math.max(0, messageId));
                return;
            }
        }
        if (fragment.getMessagesController().checkCanOpenChat(args, fragment)) {
            fragment.presentFragment(new ChatActivity(args));
        }
    }
    // endregion

    // region Making one from messages

    /** Where reminders can be made: real chats (not scheduled messages, quick replies and the like). */
    public static boolean canRemindIn(ChatActivity chat) {
        final int mode = chat.getChatMode();
        return mode == ChatActivity.MODE_DEFAULT || mode == ChatActivity.MODE_PINNED || mode == ChatActivity.MODE_SAVED;
    }

    public static boolean canRemind(MessageObject m) {
        // sent messages only (secret chats number theirs below zero)
        return m != null && m.messageOwner != null && (m.getId() > 0 || m.getId() < 0 && DialogObject.isEncryptedDialog(m.getDialogId()))
            && !m.isSponsored() && !m.isSending() && !m.isSendError() && !(m.messageOwner instanceof TLRPC.TL_messageService);
    }

    /** What a reminder is made from: the chat and its messages, copied on the UI thread. */
    public static final class Source {
        public int account;
        public long dialogId;
        public long topicId;
        public String chatName = "";
        public int chatKind; // 0 private, 1 group, 2 channel, 3 Saved Messages
        public int[] messageIds;
        public final ArrayList<Line> lines = new ArrayList<>();
        /** Message text may go to an AI here (not a secret chat, not protected content). */
        public boolean aiAllowed;
        public boolean secret;
        public boolean protectedContent;
    }

    public static final class Line {
        public String sender;
        public boolean own;
        public long date;
        public String text;
    }

    /** @return null when none of the messages can have a reminder */
    @Nullable
    public static Source collect(ChatActivity chat, List<MessageObject> messages) {
        final ArrayList<MessageObject> list = new ArrayList<>();
        for (MessageObject m : messages) {
            if (canRemind(m) && !list.contains(m)) {
                list.add(m);
            }
        }
        if (list.isEmpty()) {
            return null;
        }
        Collections.sort(list, (a, b) -> {
            final int d = Integer.compare(a.messageOwner.date, b.messageOwner.date);
            return d != 0 ? d : Integer.compare(Math.abs(a.getId()), Math.abs(b.getId()));
        });
        final Source s = new Source();
        s.account = chat.getCurrentAccount();
        s.dialogId = chat.getDialogId();
        s.topicId = chat.isTopic ? chat.getTopicId() : 0;
        s.secret = DialogObject.isEncryptedDialog(s.dialogId);
        final MessagesController mc = chat.getMessagesController();
        final long self = chat.getUserConfig().getClientUserId();
        final TLRPC.User user = chat.getCurrentUser();
        final TLRPC.Chat c = chat.getCurrentChat();
        if (s.dialogId == self) {
            s.chatKind = 3;
            s.chatName = LocaleController.getString(R.string.SavedMessages);
        } else if (user != null) {
            s.chatName = UserObject.getUserName(user);
        } else if (c != null) {
            s.chatKind = ChatObject.isChannelAndNotMegaGroup(c) ? 2 : 1;
            s.chatName = c.title != null ? c.title : "";
        }
        boolean noForwards = mc.isPeerNoForwards(s.dialogId);
        final ArrayList<Integer> ids = new ArrayList<>();
        for (MessageObject m : list) {
            if (m.messageOwner.noforwards) {
                noForwards = true;
            }
            if (m.getDialogId() == s.dialogId) {
                ids.add(m.getId()); // not the old messages of a group that became a supergroup: they live in another chat
            }
            final Line line = new Line();
            line.date = m.messageOwner.date * 1000L;
            line.own = m.isOutOwner() || m.getFromChatId() == self;
            line.sender = senderName(mc, m, line.own);
            line.text = textOf(m);
            s.lines.add(line);
        }
        s.messageIds = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            s.messageIds[i] = ids.get(i);
        }
        s.protectedContent = noForwards;
        s.aiAllowed = !s.secret && !noForwards;
        return s;
    }

    private static String senderName(MessagesController mc, MessageObject m, boolean own) {
        if (own) {
            return LocaleController.getString(R.string.FromYou);
        }
        final long from = m.getFromChatId();
        if (from > 0) {
            final TLRPC.User u = mc.getUser(from);
            if (u != null) {
                return UserObject.getUserName(u);
            }
        } else if (from < 0) {
            final TLRPC.Chat c = mc.getChat(-from);
            if (c != null && c.title != null) {
                return c.title;
            }
        }
        return "";
    }

    /** The text of a message, or what it holds when it has none ("Photo", a file name). */
    private static String textOf(MessageObject m) {
        if (!TextUtils.isEmpty(m.messageOwner.message)) {
            return m.messageOwner.message.trim();
        }
        final TLRPC.Document doc = m.getDocument();
        if (m.type == MessageObject.TYPE_FILE && doc != null) {
            final String name = FileLoader.getDocumentFileName(doc);
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
        }
        return m.messageText != null ? m.messageText.toString().trim() : "";
    }

    /** {title, body} made from the messages themselves: when AI is not used, failed, or while it works. */
    public static String[] fallbackText(Source s) {
        Line first = null;
        for (Line l : s.lines) {
            if (!TextUtils.isEmpty(l.text)) {
                first = l;
                break;
            }
        }
        if (first == null) {
            return new String[]{LocaleController.getString(R.string.SG_ReminderDefaultTitle), ""};
        }
        final String text = first.text.trim();
        final int nl = text.indexOf('\n');
        final String firstLine = collapse(nl >= 0 ? text.substring(0, nl) : text);
        final String title = clip(firstLine, TITLE_MAX);
        // what the title did not take of the first message
        String rest = title.endsWith("…") ? firstLine.substring(Math.min(firstLine.length(), title.length() - 1)).replaceFirst("^[\\s,;:.—–-]+", "") : "";
        if (nl >= 0) {
            rest = (rest + " " + text.substring(nl + 1)).trim();
        }
        final boolean showSenders = s.chatKind == 1 || s.lines.size() > 1 && s.chatKind != 2;
        final StringBuilder body = new StringBuilder();
        int shown = 0;
        int index = 0;
        for (Line l : s.lines) {
            final String part = l == first ? collapse(rest) : collapse(l.text);
            index++;
            if (TextUtils.isEmpty(part) && !(l == first && showSenders && s.lines.size() == 1)) {
                continue;
            }
            if (shown == 3) {
                body.append(" +").append(s.lines.size() - index + 1);
                break;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            final boolean sender = showSenders && !TextUtils.isEmpty(l.sender);
            if (sender) {
                body.append(l.sender);
                if (!TextUtils.isEmpty(part)) {
                    body.append(": ");
                }
            }
            body.append(clip(part, s.lines.size() == 1 ? 180 : 90));
            shown++;
        }
        return new String[]{title, body.toString()};
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** At most {@code max} characters, cut between words with an ellipsis. */
    public static String clip(String s, int max) {
        s = collapse(s);
        if (s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max * 0.6f) {
            cut = max - 1;
        }
        String out = s.substring(0, cut).trim();
        while (!out.isEmpty() && ",;:—–-".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out + "…";
    }

    private static final String INSTRUCTION = "You turn messages from a messenger chat into a short personal reminder for the user.\n"
        + "Write in " + SolegramSummarizer.LANGUAGE + ".\n"
        + "Answer with exactly two lines and nothing else.\n"
        + "Line 1: the title: what to do or remember, at most 60 characters, starting with a verb where it fits, "
        + "for example \"Call Anna about the contract\". No quotes, no emoji, no period at the end.\n"
        + "Line 2: one or two short sentences with the key details: names, dates, times, amounts, addresses, phone numbers "
        + "and links, copied exactly as written. Write \"-\" when there is nothing to add to the title.\n"
        + "Use only what the messages say; do not invent anything. Messages from \"" + "{you}" + "\" were written by the user. "
        + "No markdown, no labels such as \"Title:\".";

    /** The messages as the AI reads them: who wrote what and when, oldest first. */
    private static String aiInput(Source s, @Nullable String previousTitle) {
        final StringBuilder sb = new StringBuilder();
        final String kind = s.chatKind == 3 ? "the user's saved messages" : s.chatKind == 2 ? "channel" : s.chatKind == 1 ? "group" : "private chat";
        sb.append("Chat: ").append(s.chatName).append(" (").append(kind).append(")\n");
        final long now = System.currentTimeMillis();
        sb.append("Today: ").append(new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)).append("\n");
        sb.append("Messages, oldest first:\n");
        final java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH);
        for (Line l : s.lines) {
            sb.append('\n');
            if (!TextUtils.isEmpty(l.sender)) {
                sb.append(l.sender).append(", ");
            }
            sb.append(date.format(l.date)).append(":\n");
            sb.append(l.text.length() > 1500 ? l.text.substring(0, 1500) + "…" : l.text).append('\n');
        }
        if (!TextUtils.isEmpty(previousTitle)) {
            sb.append("\nThe previous title was \"").append(previousTitle).append("\". Word it differently this time.");
        }
        return sb.toString();
    }

    /**
     * Composes the text with AI. {@code done} gets (title, body, SolegramSummarizer.SOURCE_*) on the UI thread, or
     * (null, null, SolegramSummarizer.COMPOSE_*) when nothing was made. The caller checks {@link Source#aiAllowed}.
     */
    public static void compose(Source s, @Nullable String previousTitle, boolean mayAskConsent, Utilities.Callback3<String, String, Integer> done) {
        if (!s.aiAllowed) {
            // secret chats and protected content are not handed to any AI, not even the one on the phone
            AndroidUtilities.runOnUIThread(() -> done.run(null, null, SolegramSummarizer.COMPOSE_FAILED));
            return;
        }
        final StringBuilder sample = new StringBuilder();
        for (Line l : s.lines) {
            if (!l.own || sample.length() == 0) {
                sample.append(l.text).append('\n');
            }
        }
        final String instruction = INSTRUCTION.replace("{you}", LocaleController.getString(R.string.FromYou));
        SolegramSummarizer.compose(instruction, aiInput(s, previousTitle), sample.toString(), LocaleController.getInstance().getCurrentLocale().getLanguage(),
            200, mayAskConsent, R.string.SG_ReminderAiConsentTitle, R.string.SG_ReminderAiConsent, (answer, source) -> {
                final String[] parsed = answer != null ? parse(answer) : null;
                if (parsed == null) {
                    done.run(null, null, answer != null ? SolegramSummarizer.COMPOSE_FAILED : source);
                } else {
                    done.run(parsed[0], parsed[1], source);
                }
            });
    }

    private static final java.util.regex.Pattern LABEL = java.util.regex.Pattern.compile(
        "^(line\\s*\\d+|title|body|details|reminder|заголовок|название|текст|описание|детали|подробности|напоминание)\\s*[:：.\\-–—]\\s*",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** {title, body} from the AI answer, or null when it is unusable. */
    @Nullable
    static String[] parse(String raw) {
        final String s = raw.replaceAll("(?s)<think>.*?</think>", "").replace("**", "").replace("__", "").replace("`", "").trim();
        final ArrayList<String> lines = new ArrayList<>();
        for (String line : s.split("\n")) {
            String l = line.trim().replaceFirst("^#+\\s*", "").replaceFirst("^([-*•]|\\d+[.)])\\s+", "");
            l = LABEL.matcher(l).replaceFirst("").trim();
            l = unquote(l.replaceFirst("(?<=[\"»”])\\.$", "")); // also "Call Anna". with the period after the quote
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        String title = lines.get(0);
        while (title.endsWith(".") && !title.endsWith("..")) {
            title = title.substring(0, title.length() - 1).trim();
        }
        title = clip(title, TITLE_MAX + 10);
        if (title.isEmpty()) {
            return null;
        }
        final StringBuilder body = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            final String l = lines.get(i);
            if (l.matches("^[-–—.…]+$")) {
                continue;
            }
            if (body.length() > 0) {
                body.append(' ');
            }
            body.append(l);
        }
        return new String[]{title, clip(body.toString(), 300)};
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            final char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if (a == '"' && b == '"' || a == '«' && b == '»' || a == '“' && b == '”' || a == '\'' && b == '\'') {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
    // endregion

    // region Formatting

    private static int dayIndex(long ms) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }

    /** 0 = today, 1 = tomorrow, -1 = yesterday, ... (calendar days, not 24-hour spans). */
    public static int daysFromToday(long ms) {
        final Calendar a = Calendar.getInstance();
        final Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        for (Calendar c : new Calendar[]{a, b}) {
            c.set(Calendar.HOUR_OF_DAY, 12);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
        }
        return Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / (24f * HOUR));
    }

    public static String time(long ms) {
        return LocaleController.getInstance().getFormatterDay().format(ms);
    }

    /** "today at 19:00", "tomorrow at 9:00", "21 Oct at 19:00": for sentences. */
    public static String formatAt(long ms) {
        final int days = daysFromToday(ms);
        if (days == 0) {
            return LocaleController.formatString(R.string.SG_ReminderAtToday, time(ms));
        }
        if (days == 1) {
            return LocaleController.formatString(R.string.SG_ReminderAtTomorrow, time(ms));
        }
        return LocaleController.formatString(R.string.SG_ReminderAtDate, date(ms), time(ms));
    }

    /** "21 Oct", with the year when it is not this one. */
    public static String date(long ms) {
        final Calendar c = Calendar.getInstance();
        final int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) == year ? LocaleController.getInstance().getFormatterDayMonth().format(ms)
            : LocaleController.getInstance().getFormatterYear().format(ms);
    }

    /** "19:00" today and tomorrow, "Sat, 10:00" within a week, "21 Oct" later. */
    public static String formatShort(long ms, boolean timeForTomorrow) {
        final int days = daysFromToday(ms);
        if (days == 0 || days == 1 && timeForTomorrow) {
            return time(ms);
        }
        if (days > 0 && days < 7) {
            return LocaleController.getInstance().getFormatterWeek().format(ms) + ", " + time(ms);
        }
        return date(ms);
    }

    public static boolean sameDay(long a, long b) {
        return dayIndex(a) == dayIndex(b);
    }
    // endregion
}
