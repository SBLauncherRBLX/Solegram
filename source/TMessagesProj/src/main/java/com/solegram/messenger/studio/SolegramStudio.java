/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.studio;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Solegram Studio: scenes for trailers and screenshots, played by the app itself. A scene is a list of steps — open
 * a screen, wait, tap or swipe at a place on the screen (in parts of its width and height), go back, take a frame —
 * and is recorded into its own video. Scenes come built in, and fresh ones from studio/scenes.json in the app's
 * update repository, so new scenes need no new build. Steps only move around the app and touch it like a finger;
 * they never type or send anything themselves.
 */
public final class SolegramStudio {

    private SolegramStudio() {
    }

    private static boolean running;

    /** Whether the Studio is playing scenes now (the wallet then lets the recording see it). */
    public static boolean isRunning() {
        return running;
    }

    static void setRunning(boolean on) {
        running = on;
    }

    /** Whether a scene opens the wallet or its collection (they need the wallet unlocked first). */
    static boolean needsWallet(Scene s) {
        for (int i = 0; s.steps != null && i < s.steps.length(); i++) {
            final JSONObject o = s.steps.optJSONObject(i);
            final String open = o == null ? null : o.optString("open", null);
            if ("wallet".equals(open) || "vault".equals(open)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ scenes

    public static final class Scene {
        public String id, title;
        public JSONArray steps;
    }

    /** Scenes in the app itself: the 2.11 betas' new things. */
    public static ArrayList<Scene> builtIn() {
        final ArrayList<Scene> list = new ArrayList<>();
        try {
            list.add(scene("wallet", "SoleWallet", "["
                + "{\"open\":\"wallet\"},{\"wait\":1600},"
                + "{\"swipe\":[0.25,0.25,0.8,0.25,500]},{\"wait\":1200},{\"tap\":[0.5,0.25]},{\"wait\":1400},"
                + "{\"shot\":\"wallet-card\"},{\"swipe\":[0.5,0.8,0.5,0.35,700]},{\"wait\":1200},{\"shot\":\"wallet-goals\"},"
                + "{\"back\":true},{\"wait\":800}]"));
            list.add(scene("collection", "SoleWallet — Collection", "["
                + "{\"open\":\"vault\"},{\"wait\":2500},{\"shot\":\"collection\"},"
                + "{\"swipe\":[0.5,0.8,0.5,0.4,700]},{\"wait\":1200},{\"back\":true},{\"wait\":800}]"));
            list.add(scene("music", "Music", "["
                + "{\"open\":\"music\"},{\"wait\":1800},{\"shot\":\"music\"},"
                + "{\"swipe\":[0.5,0.8,0.5,0.4,700]},{\"wait\":1200},{\"back\":true},{\"wait\":800}]"));
            list.add(scene("player", "Player", "["
                + "{\"open\":\"player\"},{\"wait\":3000},{\"shot\":\"player\"},{\"wait\":3000},{\"back\":true},{\"wait\":800}]"));
            list.add(scene("settings", "Settings", "["
                + "{\"open\":\"settings\"},{\"wait\":1500},{\"swipe\":[0.5,0.8,0.5,0.3,800]},{\"wait\":1000},{\"shot\":\"settings\"},"
                + "{\"back\":true},{\"wait\":600},{\"open\":\"chat_settings\"},{\"wait\":1500},{\"shot\":\"chat-settings\"},"
                + "{\"back\":true},{\"wait\":800}]"));
            list.add(scene("profile", "Profile editor", "["
                + "{\"open\":\"profile_editor\"},{\"wait\":2000},{\"shot\":\"profile-editor\"},"
                + "{\"swipe\":[0.5,0.8,0.5,0.45,700]},{\"wait\":1200},{\"back\":true},{\"wait\":800}]"));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    private static Scene scene(String id, String title, String steps) throws Exception {
        final Scene s = new Scene();
        s.id = id;
        s.title = title;
        s.steps = new JSONArray(steps);
        return s;
    }

    /** The repository's scenes (studio/scenes.json: {"scenes":[{"id","title","steps":[...]}]}), or none. */
    public static void loadRemote(Utilities.Callback<ArrayList<Scene>> done) {
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<Scene> list = new ArrayList<>();
            try {
                final JSONObject o = com.solegram.messenger.updater.StudioScenes.load();
                final JSONArray a = o == null ? null : o.optJSONArray("scenes");
                for (int i = 0; a != null && i < a.length() && i < 60; i++) {
                    final JSONObject so = a.getJSONObject(i);
                    final Scene s = new Scene();
                    s.id = so.optString("id", "scene" + i).replaceAll("[^A-Za-z0-9_-]", "");
                    s.title = so.optString("title", s.id);
                    s.steps = so.optJSONArray("steps");
                    if (s.steps != null && s.steps.length() <= 200) {
                        list.add(s);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> done.run(list));
        });
    }

    // ------------------------------------------------------------------ the folder for chats

    private static android.content.SharedPreferences prefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("solegram_studio", Context.MODE_PRIVATE);
    }

    /** The chat folder scenes show the chats from (its filter id; 0 = all chats). */
    public static int folderId() {
        return prefs().getInt("folder", 0);
    }

    public static void setFolderId(int id) {
        prefs().edit().putInt("folder", id).apply();
    }

    /** Back to the main screen, on the chats tab, showing the chosen folder: where every scene starts. */
    static void home() {
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentLayout() == null) {
            return;
        }
        final java.util.List<BaseFragment> stack = last.getParentLayout().getFragmentStack();
        org.telegram.ui.MainTabsActivity main = null;
        for (BaseFragment f : stack) {
            if (f instanceof org.telegram.ui.MainTabsActivity) {
                main = (org.telegram.ui.MainTabsActivity) f;
                break;
            }
        }
        if (main == null) {
            return;
        }
        for (int i = 0; i < 16; i++) {
            final BaseFragment top = LaunchActivity.getSafeLastFragment();
            if (top == null || top == main) {
                break;
            }
            top.finishFragment(false);
        }
        main.solegramShowChatsFolder(folderId());
    }

    // ------------------------------------------------------------------ screens

    /** Opens a screen by its Studio name; false when there is no such screen or it can't open now. */
    static boolean open(String target) {
        final BaseFragment f = LaunchActivity.getSafeLastFragment();
        final Activity a = f == null ? null : f.getParentActivity();
        if (f == null || a == null || target == null) {
            return false;
        }
        final int account = UserConfig.selectedAccount;
        switch (target) {
            case "chats":
                home();
                return true;
            case "wallet":
                return f.presentFragment(new com.solegram.messenger.wallet.SoleWalletActivity());
            case "vault":
                return f.presentFragment(new com.solegram.messenger.wallet.SoleVaultActivity());
            case "music":
                return f.presentFragment(new com.solegram.messenger.ui.SolegramMusicActivity(null));
            case "player":
                openPlayer(f);
                return true;
            case "settings":
                return f.presentFragment(new org.telegram.ui.SettingsActivity());
            case "solegram_settings":
                return f.presentFragment(new com.solegram.messenger.ui.SolegramSettingsActivity());
            case "chat_settings":
                return f.presentFragment(new org.telegram.ui.ThemeActivity(org.telegram.ui.ThemeActivity.THEME_TYPE_BASIC));
            case "privacy_settings":
                return f.presentFragment(new org.telegram.ui.PrivacySettingsActivity());
            case "glass_settings":
                return f.presentFragment(new com.solegram.messenger.ui.SolegramSettingsActivity(com.solegram.messenger.ui.SolegramSettingsActivity.PAGE_GLASS));
            case "profile_editor":
                return f.presentFragment(new com.solegram.messenger.ui.SolegramProfileEditorActivity());
            case "profile": {
                final Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                args.putBoolean("my_profile", true);
                return f.presentFragment(new org.telegram.ui.ProfileActivity(args));
            }
            case "stars":
                return f.presentFragment(new org.telegram.ui.Stars.StarsIntroActivity());
        }
        // a Telegram link inside the app: a chat, a channel, a post
        if (target.startsWith("https://t.me/") || target.startsWith("tg://")) {
            org.telegram.messenger.browser.Browser.openUrl(a, target);
            return true;
        }
        return false;
    }

    private static boolean startedMusic;

    /**
     * Solegram's player shows what plays; with nothing playing the app opens Telegram's old one. So when nothing
     * plays, the Studio first plays a track from the music in the person's chats (the Music tab's "From your chats"),
     * then opens the player.
     */
    private static void openPlayer(BaseFragment f) {
        final org.telegram.messenger.MessageObject now = org.telegram.messenger.MediaController.getInstance().getPlayingMessageObject();
        if (now != null && now.isMusic()) {
            com.solegram.messenger.ui.SolegramPlayer.open(f.getParentActivity(), f.getResourceProvider());
            return;
        }
        final int account = f.getCurrentAccount();
        final org.telegram.tgnet.TLRPC.TL_messages_searchGlobal req = new org.telegram.tgnet.TLRPC.TL_messages_searchGlobal();
        req.q = "";
        req.filter = new org.telegram.tgnet.TLRPC.TL_inputMessagesFilterMusic();
        req.limit = 20;
        req.offset_peer = new org.telegram.tgnet.TLRPC.TL_inputPeerEmpty();
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<org.telegram.messenger.MessageObject> list = new ArrayList<>();
            if (response instanceof org.telegram.tgnet.TLRPC.messages_Messages) {
                final org.telegram.tgnet.TLRPC.messages_Messages res = (org.telegram.tgnet.TLRPC.messages_Messages) response;
                org.telegram.messenger.MessagesController.getInstance(account).putUsers(res.users, false);
                org.telegram.messenger.MessagesController.getInstance(account).putChats(res.chats, false);
                for (org.telegram.tgnet.TLRPC.Message m : res.messages) {
                    final org.telegram.messenger.MessageObject mo = new org.telegram.messenger.MessageObject(account, m, false, true);
                    if (mo.isMusic()) {
                        list.add(mo);
                    }
                }
            }
            final BaseFragment top = LaunchActivity.getSafeLastFragment();
            if (list.isEmpty() || top == null || top.getParentActivity() == null) {
                return;
            }
            if (org.telegram.messenger.MediaController.getInstance().setPlaylist(list, list.get(0), 0)) {
                startedMusic = true;
                com.solegram.messenger.ui.SolegramPlayer.open(top.getParentActivity(), top.getResourceProvider());
            }
        }));
    }

    /** The music the Studio started for a scene stops when the Studio is done. */
    static void stopOwnMusic() {
        if (!startedMusic) {
            return;
        }
        startedMusic = false;
        final org.telegram.messenger.MessageObject now = org.telegram.messenger.MediaController.getInstance().getPlayingMessageObject();
        if (now != null) {
            org.telegram.messenger.MediaController.getInstance().pauseMessage(now);
        }
    }

    // ------------------------------------------------------------------ touches

    /** A finger's tap at a place on the screen (parts of the width and height). */
    static void tap(Activity a, float fx, float fy, @Nullable TouchMarks marks) {
        final View root = a.getWindow().getDecorView();
        final float x = fx * root.getWidth(), y = fy * root.getHeight();
        final long t = SystemClock.uptimeMillis();
        dispatch(a, MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        if (marks != null) {
            marks.tap(x, y);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> dispatch(a, MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)), 90);
    }

    /** A finger's swipe from one place to another over a time, with a natural ease. */
    static void swipe(Activity a, float fx1, float fy1, float fx2, float fy2, long ms, @Nullable TouchMarks marks) {
        final View root = a.getWindow().getDecorView();
        final float x1 = fx1 * root.getWidth(), y1 = fy1 * root.getHeight(), x2 = fx2 * root.getWidth(), y2 = fy2 * root.getHeight();
        final long down = SystemClock.uptimeMillis();
        final Handler h = new Handler(Looper.getMainLooper());
        dispatch(a, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0));
        final int steps = (int) Math.max(6, ms / 16);
        for (int i = 1; i <= steps; i++) {
            final float p = i / (float) steps;
            final float e = p < 0.5f ? 2 * p * p : 1 - (float) Math.pow(-2 * p + 2, 2) / 2;
            final float x = x1 + (x2 - x1) * e, y = y1 + (y2 - y1) * e;
            final boolean last = i == steps;
            h.postDelayed(() -> {
                dispatch(a, MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0));
                if (marks != null) {
                    marks.drag(x, y, last);
                }
                if (last) {
                    dispatch(a, MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0));
                }
            }, (long) (ms * p));
        }
    }

    private static void dispatch(Activity a, MotionEvent e) {
        try {
            a.dispatchTouchEvent(e);
        } catch (Exception ex) {
            FileLog.e(ex);
        }
        e.recycle();
    }

    /** Soft circles where the Studio's finger touches, so a viewer sees what is tapped. */
    static final class TouchMarks extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x, y;
        private float tapT = -1, dragAlpha;
        private long tapStart;

        TouchMarks(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        static TouchMarks attach(Activity a) {
            final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            final TouchMarks m = new TouchMarks(a);
            decor.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return m;
        }

        void detach() {
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        }

        void tap(float x, float y) {
            this.x = x;
            this.y = y;
            tapStart = SystemClock.elapsedRealtime();
            tapT = 0;
            bringToFront();
            invalidate();
        }

        void drag(float x, float y, boolean end) {
            this.x = x;
            this.y = y;
            dragAlpha = end ? 0 : 1;
            bringToFront();
            invalidate();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float r = AndroidUtilities.dp(22);
            if (tapT >= 0) {
                tapT = Math.min(1f, (SystemClock.elapsedRealtime() - tapStart) / 450f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha((int) (110 * (1 - tapT)));
                canvas.drawCircle(x, y, r * (0.7f + 0.5f * tapT), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(2));
                paint.setAlpha((int) (200 * (1 - tapT)));
                canvas.drawCircle(x, y, r * (0.7f + 0.8f * tapT), paint);
                if (tapT >= 1f) {
                    tapT = -1;
                } else {
                    postInvalidateOnAnimation();
                }
            }
            if (dragAlpha > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha(100);
                canvas.drawCircle(x, y, r * 0.8f, paint);
            }
        }
    }

    // ------------------------------------------------------------------ frames

    /** A frame of the app's window, at the screen's full size, saved to Pictures/Solegram Studio. */
    static void shot(Activity a, String name, Utilities.Callback<String> done) {
        final View root = a.getWindow().getDecorView();
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            done.run(null);
            return;
        }
        final Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        final String file = "Solegram-" + name.replaceAll("[^A-Za-z0-9_-]", "") + "-" + System.currentTimeMillis() + ".png";
        final Utilities.Callback<Bitmap> save = b -> Utilities.globalQueue.postRunnable(() -> {
            final String where = savePng(a, b, file);
            b.recycle();
            AndroidUtilities.runOnUIThread(() -> done.run(where));
        });
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(a.getWindow(), bitmap, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        save.run(bitmap);
                    } else {
                        root.draw(new Canvas(bitmap));
                        save.run(bitmap);
                    }
                }, new Handler(Looper.getMainLooper()));
                return;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        root.draw(new Canvas(bitmap));
        save.run(bitmap);
    }

    @Nullable
    private static String savePng(Context context, Bitmap b, String file) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                final ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, file);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Solegram Studio");
                final Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return null;
                }
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                return uri.toString();
            }
            final File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Solegram Studio");
            dir.mkdirs();
            final File f = new File(dir, file);
            try (OutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
