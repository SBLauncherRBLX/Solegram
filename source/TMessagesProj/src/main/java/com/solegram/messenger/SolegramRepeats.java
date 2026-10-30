/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;

/**
 * Repeating scheduled messages without Premium (Telegram repeats them on its server only for Premium): the message
 * is scheduled once more for every repeat ahead (a few at a time, never more than a year out), and the app adds the
 * next ones whenever it opens. Text messages only; the repeat is stopped in Solegram's settings.
 */
public final class SolegramRepeats {

    private static final String KEY = "scheduledRepeats";
    /** How many copies wait on the server at a time. */
    private static final int AHEAD = 8;
    /** Telegram schedules up to a year ahead; a little margin. */
    private static final int HORIZON = 360 * 86400;

    public static final class Rule {
        public final int account;
        public final long dialogId;
        public final String text;
        public final int period;
        /** The last date a copy was scheduled for, unix seconds. */
        public int last;
        public final boolean notify;

        Rule(int account, long dialogId, String text, int period, int last, boolean notify) {
            this.account = account;
            this.dialogId = dialogId;
            this.text = text;
            this.period = period;
            this.last = last;
            this.notify = notify;
        }
    }

    private SolegramRepeats() {
    }

    public static synchronized ArrayList<Rule> rules() {
        final ArrayList<Rule> list = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(SolegramConfig.prefs().getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                list.add(new Rule(o.getInt("a"), o.getLong("d"), o.getString("t"), o.getInt("p"), o.getInt("l"), o.optBoolean("n", true)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return list;
    }

    private static void save(ArrayList<Rule> list) {
        try {
            final JSONArray array = new JSONArray();
            for (Rule r : list) {
                final JSONObject o = new JSONObject();
                o.put("a", r.account);
                o.put("d", r.dialogId);
                o.put("t", r.text);
                o.put("p", r.period);
                o.put("l", r.last);
                o.put("n", r.notify);
                array.put(o);
            }
            SolegramConfig.prefs().edit().putString(KEY, array.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * A message is being scheduled with a repeat the account cannot have on the server: it goes out once as asked,
     * and the copies for the repeats after it are scheduled here.
     *
     * @return true when the repeat was taken over (the message is then sent without it)
     */
    public static boolean takeOver(int account, SendMessagesHelper.SendMessageParams params) {
        if (params == null || params.scheduleRepeatPeriod <= 0 || params.scheduleDate <= 0 || UserConfig.getInstance(account).isPremium()) {
            return false;
        }
        final int period = params.scheduleRepeatPeriod;
        params.scheduleRepeatPeriod = 0;
        final boolean textOnly = !TextUtils.isEmpty(params.message) && params.photo == null && params.document == null && params.location == null
            && params.poll == null && params.todo == null && params.game == null && params.user == null && params.invoice == null && params.path == null;
        if (!textOnly) {
            return true; // sent once: only text messages are repeated here
        }
        final Rule rule = new Rule(account, params.peer, params.message, period, params.scheduleDate, params.notify);
        synchronized (SolegramRepeats.class) {
            final ArrayList<Rule> list = rules();
            list.add(rule);
            save(list);
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(SolegramRepeats::tick, 1500);
        return true;
    }

    /** Schedules the copies that are due to wait on the server now (when the app opens and after a new repeat). */
    public static void tick() {
        final ArrayList<Rule> list;
        synchronized (SolegramRepeats.class) {
            list = rules();
        }
        if (list.isEmpty()) {
            return;
        }
        final int now = (int) (System.currentTimeMillis() / 1000);
        boolean changed = false;
        for (Rule r : list) {
            if (r.period <= 0 || !UserConfig.isValidAccount(r.account)) {
                continue;
            }
            // a long time away: start again from the next date to come
            if (r.last < now) {
                final int missed = (now - r.last) / r.period;
                r.last += missed * r.period;
            }
            int sent = 0;
            while (r.last + r.period <= now + Math.min(HORIZON, AHEAD * r.period) && sent < AHEAD) {
                r.last += r.period;
                if (r.last <= now + 60) {
                    continue;
                }
                final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(r.text, r.dialogId);
                params.scheduleDate = r.last;
                params.notify = r.notify;
                SendMessagesHelper.getInstance(r.account).sendMessage(params);
                sent++;
                changed = true;
            }
        }
        if (changed) {
            synchronized (SolegramRepeats.class) {
                save(list);
            }
        }
    }

    /** Stops a repeat; the copies already scheduled stay until the user deletes them. */
    public static synchronized void remove(int index) {
        final ArrayList<Rule> list = rules();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            save(list);
        }
    }
}
