/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TimeZone;

/**
 * Solegram Streak: a daily chat streak with one person. A day counts when both of you wrote at least one
 * message that day (local time). The streak is the run of such days ending today or yesterday; it shows from
 * {@link #MIN_DAYS} days as the Solegram sun next to the name, and the sun changes colour as it grows.
 * Everything is worked out on the device from the messages Telegram already keeps, so nothing is sent anywhere.
 */
public final class SolegramStreak {

    public static final int MIN_DAYS = 3;
    /** First day of each level. */
    public static final int[] LEVEL_FROM = {3, 7, 30, 100, 365};
    /** The flame of each level: its top, its bottom and its core. */
    public static final int[][] LEVEL_COLORS = {
        {0xFFFFD45A, 0xFFFF9A1F, 0xFFFFF3B8},
        {0xFFFF8A1F, 0xFFFF4A1A, 0xFFFFD866},
        {0xFFFF5A2E, 0xFFD6143A, 0xFFFFB347},
        {0xFFC84DFF, 0xFFFF4FB8, 0xFFFFD1F4},
        {0xFF3D7BFF, 0xFF36E0FF, 0xFFFFFFFF},
    };
    /** A streak at risk (nobody wrote today yet): the flame goes grey. */
    private static final int[] AT_RISK_COLORS = {0xFFB4B9C2, 0xFF8A909B, 0xFFE4E7EC};
    public static final int[] LEVEL_NAMES = {
        R.string.SG_StreakLevel0, R.string.SG_StreakLevel1, R.string.SG_StreakLevel2, R.string.SG_StreakLevel3, R.string.SG_StreakLevel4,
    };
    /** Text colours, readable on both light and dark list backgrounds. */
    private static final int[] LEVEL_TEXT = {0xFFF29A12, 0xFFF0661A, 0xFFE3324C, 0xFFC046E8, 0xFF2F8CFF};

    private static final long DAY_MS = 86_400_000L;
    private static final long RECHECK_MS = 20 * 60_000L;

    public static final class State {
        public int count;
        /** Both of you already wrote today. */
        public boolean today;
        public long lastFullDay;
        long checkedAt;
        long checkedDay;
        boolean busy;
        boolean dirty;
    }

    private static final class Live {
        final HashSet<Long> out = new HashSet<>();
        final HashSet<Long> in = new HashSet<>();
    }

    private static final HashMap<String, State> states = new HashMap<>();
    private static final HashMap<String, Live> live = new HashMap<>();
    private static final SparseArray<Bitmap> suns = new SparseArray<>();
    private static final boolean[] notifyPosted = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private SolegramStreak() {
    }

    // ------------------------------------------------------------------ days

    public static long dayOf(long unixSeconds) {
        final long ms = unixSeconds * 1000L;
        return Math.floorDiv(ms + TimeZone.getDefault().getOffset(ms), DAY_MS);
    }

    public static long today() {
        return dayOf(System.currentTimeMillis() / 1000L);
    }

    // ------------------------------------------------------------------ state

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_streaks", Context.MODE_PRIVATE);
    }

    static String key(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    public static boolean eligible(int account, long dialogId) {
        if (!DialogObject.isUserDialog(dialogId) || dialogId == UserConfig.getInstance(account).getClientUserId() || dialogId == 777000L) {
            return false;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && !user.bot && !UserObject.isDeleted(user) && !UserObject.isReplyUser(user) && !UserObject.isAnonymous(user);
    }

    /** The streak with this person, or null when it does not apply. Starts a background check when stale. */
    @Nullable
    public static State state(int account, long dialogId) {
        if (!SolegramConfig.streaks || !eligible(account, dialogId)) {
            return null;
        }
        final String k = key(account, dialogId);
        State s = states.get(k);
        if (s == null) {
            s = new State();
            final SharedPreferences p = prefs();
            s.count = p.getInt(k, 0);
            s.lastFullDay = p.getLong(k + "_d", 0);
            s.dirty = true;
            states.put(k, s);
        }
        final long now = System.currentTimeMillis();
        if (!s.busy && (s.dirty || now - s.checkedAt > RECHECK_MS || s.checkedDay != today())) {
            refresh(account, dialogId, s);
        }
        return s;
    }

    /** Days in the streak right now (0 when there is none, or it is shorter than {@link #MIN_DAYS}). */
    public static int days(int account, long dialogId) {
        final State s = state(account, dialogId);
        return s == null ? 0 : alive(s);
    }

    static int alive(State s) {
        if (s.count < MIN_DAYS || s.lastFullDay < today() - 1) {
            return 0;
        }
        return s.count;
    }

    /** The streak runs out tonight unless both of you write: shown dimmed from the evening on. */
    public static boolean atRisk(int account, long dialogId) {
        final State s = states.get(key(account, dialogId));
        if (s == null || s.today || alive(s) == 0) {
            return false;
        }
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= 18;
    }

    public static boolean doneToday(int account, long dialogId) {
        final State s = states.get(key(account, dialogId));
        return s != null && s.today;
    }

    public static int level(int days) {
        int l = 0;
        for (int i = 0; i < LEVEL_FROM.length; i++) {
            if (days >= LEVEL_FROM[i]) {
                l = i;
            }
        }
        return l;
    }

    /** Days until the next level, or 0 at the top. */
    public static int toNextLevel(int days) {
        final int l = level(days);
        return l + 1 < LEVEL_FROM.length ? LEVEL_FROM[l + 1] - days : 0;
    }

    /** The chats with a streak now, longest first, as last worked out (dialog id, days). */
    public static ArrayList<long[]> active(int account) {
        final ArrayList<long[]> out = new ArrayList<>();
        final String prefix = account + "_";
        final long t = today();
        for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            final String k = e.getKey();
            if (!k.startsWith(prefix) || k.endsWith("_d") || !(e.getValue() instanceof Integer)) {
                continue;
            }
            final int count = (Integer) e.getValue();
            final long last = prefs().getLong(k + "_d", 0);
            if (count < MIN_DAYS || last < t - 1) {
                continue;
            }
            try {
                out.add(new long[] {Long.parseLong(k.substring(prefix.length())), count});
            } catch (NumberFormatException ignore) {
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b[1], a[1]));
        return out;
    }

    /** New messages in a chat: today's part is known before the database has them. */
    public static void onMessages(int account, long dialogId, ArrayList<MessageObject> messages) {
        if (!SolegramConfig.streaks || messages == null || messages.isEmpty() || !DialogObject.isUserDialog(dialogId)) {
            return;
        }
        final String k = key(account, dialogId);
        Live lv = live.get(k);
        if (lv == null) {
            lv = new Live();
            live.put(k, lv);
        }
        final long minDay = today() - 2;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null || m.scheduled) {
                continue;
            }
            final long d = dayOf(m.messageOwner.date);
            if (d < minDay) {
                continue;
            }
            (m.isOut() ? lv.out : lv.in).add(d);
        }
        final State s = states.get(k);
        if (s != null) {
            s.dirty = true;
            if (!s.busy) {
                refresh(account, dialogId, s);
            }
        } else {
            state(account, dialogId);
        }
    }

    private static void refresh(int account, long dialogId, State s) {
        s.busy = true;
        final String k = key(account, dialogId);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final long since = System.currentTimeMillis() / 1000L - 800L * 86400L;
        storage.getStorageQueue().postRunnable(() -> {
            final HashSet<Long> out = new HashSet<>();
            final HashSet<Long> in = new HashSet<>();
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT date, out FROM messages_v2 WHERE uid = " + dialogId + " AND date > " + since);
                while (cursor.next()) {
                    final long d = dayOf(cursor.intValue(0));
                    (cursor.intValue(1) != 0 ? out : in).add(d);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                final Live lv = live.get(k);
                if (lv != null) {
                    out.addAll(lv.out);
                    in.addAll(lv.in);
                }
                final long t = today();
                final long start = out.contains(t) && in.contains(t) ? t : t - 1;
                int n = 0;
                long d = start;
                while (out.contains(d) && in.contains(d)) {
                    n++;
                    d--;
                }
                final int before = alive(s);
                final boolean todayBefore = s.today;
                s.count = n;
                s.lastFullDay = n > 0 ? start : 0;
                s.today = n > 0 && start == t;
                s.checkedAt = System.currentTimeMillis();
                s.checkedDay = t;
                s.busy = false;
                s.dirty = false;
                if (before != alive(s) || todayBefore != s.today) {
                    prefs().edit().putInt(k, s.count).putLong(k + "_d", s.lastFullDay).apply();
                    notifyChanged(account);
                }
                if (SolegramProfileCard.pinnedStreakDialog(account) == dialogId) {
                    SolegramProfileCard.refreshOwnStreak(account);
                }
            });
        });
    }

    private static void notifyChanged(int account) {
        if (notifyPosted[account]) {
            return;
        }
        notifyPosted[account] = true;
        AndroidUtilities.runOnUIThread(() -> {
            notifyPosted[account] = false;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
        }, 350);
    }

    // ------------------------------------------------------------------ look

    /** Sun colours a streak can take instead of its level's: light, deep, and a readable text colour. */
    public static final int[] COLOR_NAMES = {
        R.string.SG_StreakColorStreak, R.string.SG_StreakColorSun, R.string.SG_StreakColorMint, R.string.SG_StreakColorLavender,
        R.string.SG_StreakColorOcean, R.string.SG_StreakColorCherry, R.string.SG_StreakColorNight,
    };
    private static final int[][] PALETTE = {
        null,
        {0xFFFFE38A, 0xFFFFA930, 0xFFF29A12},
        {0xFF9FEFCB, 0xFF18A877, 0xFF16A070},
        {0xFFDCC8FF, 0xFF8257F5, 0xFF8A5CF0},
        {0xFFA8DDFF, 0xFF2F7CF6, 0xFF2F7CF6},
        {0xFFFFB9CC, 0xFFEC3D6A, 0xFFE33D68},
        {0xFFB6C2DE, 0xFF4A5A86, 0xFF6878A6},
    };
    public static final int STYLE_FULL = 0, STYLE_SUN = 1, STYLE_NUMBER = 2, STYLE_HIDDEN = 3;
    public static final int[] STYLE_NAMES = {R.string.SG_StreakStyleFull, R.string.SG_StreakStyleSun, R.string.SG_StreakStyleNumber, R.string.SG_StreakStyleHidden};

    private static SharedPreferences lookPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_streak_look", Context.MODE_PRIVATE);
    }

    public static int colorOf(int account, long dialogId) {
        return Math.max(0, Math.min(PALETTE.length - 1, lookPrefs().getInt(key(account, dialogId) + "_c", 0)));
    }

    public static int styleOf(int account, long dialogId) {
        return Math.max(0, Math.min(STYLE_HIDDEN, lookPrefs().getInt(key(account, dialogId) + "_s", STYLE_FULL)));
    }

    public static void setLook(int account, long dialogId, int color, int style) {
        lookPrefs().edit().putInt(key(account, dialogId) + "_c", color).putInt(key(account, dialogId) + "_s", style).apply();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }

    /** The sun's gradient for a colour choice; choice 0 follows the streak level. */
    public static int[] sunColors(int color, int days) {
        final int[] f = flameColors(color, days);
        return new int[] {f[0], f[1]};
    }

    /** The flame's top, bottom and core for a colour choice; choice 0 follows the streak level. */
    public static int[] flameColors(int color, int days) {
        if (color > 0 && color < PALETTE.length) {
            final int light = PALETTE[color][0], deep = PALETTE[color][1];
            return new int[] {deep, androidx.core.graphics.ColorUtils.blendARGB(deep, light, 0.35f), androidx.core.graphics.ColorUtils.blendARGB(light, 0xFFFFFFFF, 0.45f)};
        }
        return LEVEL_COLORS[level(days)];
    }

    public static int textColor(int color, int days) {
        if (color > 0 && color < PALETTE.length) {
            return PALETTE[color][2];
        }
        return LEVEL_TEXT[level(days)];
    }

    public static int textColor(int days) {
        return textColor(0, days);
    }

    // ------------------------------------------------------------------ the sun

    // The flame of each level in a 100 x 100 box: it grows tongues as the streak grows (a round spark, one tongue,
    // two, three), and its core follows; the last one has sparks round it.
    private static final String[] FLAME = {
        "M50 22 C58 36 76 48 76 68 C76 84 64 94 50 94 C36 94 24 84 24 68 C24 50 42 40 50 22 Z",
        "M52 8 C60 26 80 40 80 64 C80 82 67 95 50 95 C33 95 20 82 20 64 C20 50 27 40 34 32 C35 42 40 48 45 50 C42 34 46 20 52 8 Z",
        "M52 4 C60 22 72 30 76 44 C79 38 80 32 79 26 C88 38 88 52 86 64 C84 82 69 96 50 96 C31 96 16 82 16 64 C16 50 22 40 30 32 C31 42 36 48 42 50 C38 32 44 16 52 4 Z",
        "M50 2 C57 18 64 28 66 42 C70 36 72 28 70 20 C82 32 88 48 86 64 C84 83 69 97 50 97 C31 97 16 83 14 64 C12 48 18 32 30 20 C28 28 30 36 34 42 C36 28 43 16 50 2 Z",
    };
    private static final String[] CORE = {
        "M50 58 C55 66 62 72 62 80 C62 88 57 92 50 92 C43 92 38 88 38 80 C38 72 45 66 50 58 Z",
        "M50 50 C56 60 65 66 65 77 C65 87 58 93 50 93 C42 93 35 87 35 77 C35 70 40 64 44 61 C45 66 47 68 49 68 C47 62 48 56 50 50 Z",
        "M50 46 C57 57 67 64 67 76 C67 87 59 94 50 94 C41 94 33 87 33 76 C33 68 38 62 43 58 C44 64 46 67 48 67 C46 60 47 52 50 46 Z",
        "M50 42 C56 52 60 58 61 64 C63 61 64 58 63 55 C68 62 69 70 68 77 C67 88 59 95 50 95 C41 95 33 88 32 77 C31 70 33 63 37 58 C37 62 38 65 40 67 C41 58 45 50 50 42 Z",
    };
    private static final android.graphics.Path[] flamePaths = new android.graphics.Path[FLAME.length], corePaths = new android.graphics.Path[CORE.length];

    private static synchronized android.graphics.Path flamePath(int shape, boolean core) {
        final android.graphics.Path[] cache = core ? corePaths : flamePaths;
        if (cache[shape] == null) {
            cache[shape] = androidx.core.graphics.PathParser.createPathFromPathData((core ? CORE : FLAME)[shape]);
        }
        return cache[shape];
    }

    /** A four-pointed spark round the eternal flame, in the 100 box. */
    public static void spark(Canvas c, Paint p, float x, float y, float r) {
        final android.graphics.Path path = new android.graphics.Path();
        final float k = r * 0.15f;
        path.moveTo(x, y - r);
        path.cubicTo(x + k, y - k, x + k, y - k, x + r, y);
        path.cubicTo(x + k, y + k, x + k, y + k, x, y + r);
        path.cubicTo(x - k, y + k, x - k, y + k, x - r, y);
        path.cubicTo(x - k, y - k, x - k, y - k, x, y - r);
        path.close();
        c.drawPath(path, p);
    }

    /** A flame's flicker for the time t (seconds): a sway and a stretch round the middle of its base. */
    public static void flicker(Canvas canvas, float t, float baseX, float baseY, float grow) {
        final float stretch = 1f + 0.045f * (float) Math.sin(t * 5.3f) + 0.02f * (float) Math.sin(t * 11.7f);
        final float squeeze = 1f - 0.025f * (float) Math.sin(t * 5.3f + 0.6f);
        final float sway = 0.05f * (float) Math.sin(t * 3.1f) + 0.02f * (float) Math.sin(t * 7.9f);
        canvas.scale(squeeze * grow, stretch * grow, baseX, baseY);
        canvas.skew(sway, 0);
        canvas.translate(-sway * baseY, 0);
    }

    /** The streak's flame: its shape from the level, its colours from the level or the chosen colour. */
    public static Bitmap sun(int color, int days, int size) {
        return flame(color, days, size, false);
    }

    public static Bitmap flame(int color, int days, int size, boolean atRisk) {
        return flameLayer(color, days, size, atRisk, LAYER_FULL);
    }

    /** The whole flame, only its outer body, or only its core (the animated flame moves them apart). */
    public static final int LAYER_FULL = 0, LAYER_BODY = 1, LAYER_CORE = 2;

    public static int[] flameColors(int color, int days, boolean atRisk) {
        return atRisk ? AT_RISK_COLORS : flameColors(color, days);
    }

    public static Bitmap flameLayer(int color, int days, int size, boolean atRisk, int layer) {
        size = Math.max(4, Math.min(size, 1024));
        final int level = level(days);
        final int cacheKey = ((((atRisk ? 30 : color > 0 ? 10 + color : 0) * 8 + level) * 3 + layer) * 2048) + size;
        Bitmap b = suns.get(cacheKey);
        if (b != null && !b.isRecycled()) {
            return b;
        }
        final int[] colors = atRisk ? AT_RISK_COLORS : flameColors(color, days);
        final int shape = Math.min(level, FLAME.length - 1);
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(b);
        final float scale = size / 100f;
        c.scale(scale, scale);
        if (layer != LAYER_CORE) {
            final Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setShader(new LinearGradient(0, 0, 30, 100, colors[0], colors[1], Shader.TileMode.CLAMP));
            c.drawPath(flamePath(shape, false), g);
        }
        if (layer != LAYER_BODY) {
            final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
            core.setShader(new LinearGradient(0, 40, 0, 100, (colors[2] & 0x00FFFFFF) | 0xD9000000, colors[2], Shader.TileMode.CLAMP));
            c.drawPath(flamePath(shape, true), core);
        }
        if (layer == LAYER_FULL && level >= 4 && !atRisk) {
            final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFF6FD3FF);
            spark(c, sp, 88, 14, 9);
            spark(c, sp, 10, 30, 6);
            spark(c, sp, 92, 86, 5);
        }
        suns.put(cacheKey, b);
        return b;
    }

    /** The sun and/or the number of days, drawn inline after a name. */
    public static final class Span extends ReplacementSpan {
        public final int days;
        private final boolean dim;
        private final int color;
        private final int style;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final String text;

        public Span(int days, boolean dim) {
            this(days, dim, 0, STYLE_FULL);
        }

        public Span(int days, boolean dim, int color, int style) {
            this.days = days;
            this.dim = dim;
            this.color = color;
            this.style = style;
            this.text = String.valueOf(days);
            textPaint.setTypeface(AndroidUtilities.bold());
        }

        private int icon(Paint p) {
            return style == STYLE_NUMBER ? 0 : Math.round(p.getTextSize() * 1.05f);
        }

        private void prepare(Paint p) {
            textPaint.setTextSize(p.getTextSize() * 0.86f);
        }

        private boolean hasText() {
            return style != STYLE_SUN;
        }

        public int width(Paint p) {
            prepare(p);
            final int icon = icon(p);
            return dp(5) + icon + (hasText() ? (icon > 0 ? dp(2) : 0) + (int) Math.ceil(textPaint.measureText(text)) : 0);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence t, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }
            return width(paint);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence t, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            prepare(paint);
            final int size = icon(paint);
            final Paint.FontMetrics fm = paint.getFontMetrics();
            final float centerY = y + (fm.ascent + fm.descent) / 2f;
            final int alpha = dim ? 120 : 255;
            float left = x + dp(5);
            if (size > 0) {
                bitmapPaint.setAlpha(dim ? 255 : alpha);
                canvas.drawBitmap(flame(color, days, size, dim), left, centerY - size / 2f, bitmapPaint);
                left += size + dp(2);
            }
            if (hasText()) {
                textPaint.setColor(dim ? 0xFF8A909B : textColor(color, days));
                textPaint.setAlpha(dim ? 255 : alpha);
                canvas.drawText(text, left, y, textPaint);
            }
        }
    }

    @Nullable
    public static Span span(int account, long dialogId) {
        final int d = days(account, dialogId);
        if (d <= 0) {
            return null;
        }
        final int style = styleOf(account, dialogId);
        return style == STYLE_HIDDEN ? null : new Span(d, atRisk(account, dialogId), colorOf(account, dialogId), style);
    }

    /** The name with the streak sun after it, or the name as is. */
    public static CharSequence withBadge(int account, long dialogId, CharSequence name) {
        final Span s = name == null ? null : span(account, dialogId);
        if (s == null) {
            return name;
        }
        final SpannableStringBuilder b = new SpannableStringBuilder(name);
        final int at = b.length();
        b.append('☀');
        b.setSpan(s, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** Appends a span made by {@link #span}. */
    public static CharSequence append(CharSequence name, Span s) {
        final SpannableStringBuilder b = new SpannableStringBuilder(name);
        final int at = b.length();
        b.append('☀');
        b.setSpan(s, at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }
}
