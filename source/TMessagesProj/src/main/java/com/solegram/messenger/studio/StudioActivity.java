/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.studio;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Solegram Studio's screen: the scenes (built in, and the repository's), and recording them — a video of each
 * scene, frames where a scene takes them, or both. Files go to Movies and Pictures, "Solegram Studio".
 */
public class StudioActivity extends BaseFragment {

    private LinearLayout column;
    private final ArrayList<SolegramStudio.Scene> scenes = new ArrayList<>();
    private boolean remoteLoaded, showTouches = true, running;
    private int savedVideos, savedShots;
    private TextView status;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Solegram Studio");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        final ScrollView scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + dp(8), 0, dp(24));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(actionBar);
        fragmentView = root;
        scenes.addAll(SolegramStudio.builtIn());
        SolegramStudio.loadRemote(list -> {
            remoteLoaded = true;
            // the repository's scenes come first; a scene with a built-in's id replaces it
            for (int i = list.size() - 1; i >= 0; i--) {
                final SolegramStudio.Scene s = list.get(i);
                for (int j = scenes.size() - 1; j >= 0; j--) {
                    if (scenes.get(j).id.equals(s.id)) {
                        scenes.remove(j);
                    }
                }
                scenes.add(0, s);
            }
            render();
        });
        render();
        return root;
    }

    private int text() {
        return getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int gray() {
        return getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
    }

    private int accent() {
        return getThemedColor(Theme.key_featuredStickers_addButton);
    }

    private void render() {
        if (column == null || getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        column.removeAllViews();
        final TextView info = new TextView(context);
        info.setText(getString(R.string.SG_StudioInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(gray());
        info.setLineSpacing(dp(2), 1f);
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 12));

        final LinearLayout panel = panel(context);
        final TextCheckCell touches = new TextCheckCell(context);
        touches.setTextAndCheck(getString(R.string.SG_StudioShowTouches), showTouches, false);
        touches.setBackground(Theme.getSelectorDrawable(false));
        touches.setOnClickListener(v -> {
            showTouches = !showTouches;
            touches.setChecked(showTouches);
        });
        panel.addView(touches);
        // the chat folder whose chats are shown: so private chats stay out of the recording
        final org.telegram.ui.Cells.TextSettingsCell folder = new org.telegram.ui.Cells.TextSettingsCell(context);
        folder.setTextAndValue(getString(R.string.SG_StudioFolder), folderName(SolegramStudio.folderId()), false);
        folder.setBackground(Theme.getSelectorDrawable(false));
        folder.setOnClickListener(v -> pickFolder(folder));
        panel.addView(folder);
        column.addView(panel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        final LinearLayout all = new LinearLayout(context);
        all.addView(button(context, getString(R.string.SG_StudioRecordAll), true, () -> start(new ArrayList<>(scenes), true)),
            LayoutHelper.createLinear(0, 46, 1f, 0, 0, 6, 0));
        all.addView(button(context, getString(R.string.SG_StudioShotsAll), false, () -> start(new ArrayList<>(scenes), false)),
            LayoutHelper.createLinear(0, 46, 1f, 6, 0, 0, 0));
        column.addView(all, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 0));

        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        status.setTextColor(gray());
        status.setGravity(Gravity.CENTER);
        status.setText(remoteLoaded ? formatString(R.string.SG_StudioScenesCount, scenes.size()) : getString(R.string.SG_StudioLoading));
        column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 4));

        final LinearLayout list = panel(context);
        for (SolegramStudio.Scene s : scenes) {
            final LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(10), dp(10));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(context);
            title.setText(s.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(text());
            texts.addView(title);
            final TextView sub = new TextView(context);
            sub.setText(formatString(R.string.SG_StudioSteps, s.steps.length(), s.id));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sub.setTextColor(gray());
            texts.addView(sub);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final ArrayList<SolegramStudio.Scene> one = new ArrayList<>();
            one.add(s);
            row.addView(small(context, "●", () -> start(one, true)), LayoutHelper.createLinear(40, 32, 6, 0, 0, 0));
            row.addView(small(context, "▶", () -> start(one, false)), LayoutHelper.createLinear(40, 32, 6, 0, 0, 0));
            list.addView(row);
        }
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));
        final TextView foot = new TextView(context);
        foot.setText(getString(R.string.SG_StudioFootnote));
        foot.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        foot.setTextColor(gray());
        foot.setLineSpacing(dp(2), 1f);
        column.addView(foot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
    }

    private String folderName(int id) {
        if (id != 0) {
            for (org.telegram.messenger.MessagesController.DialogFilter f : getMessagesController().getDialogFilters()) {
                if (f.id == id) {
                    return f.name;
                }
            }
        }
        return getString(R.string.SG_StudioFolderAll);
    }

    private void pickFolder(org.telegram.ui.Cells.TextSettingsCell cell) {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>();
        final ArrayList<CharSequence> names = new ArrayList<>();
        ids.add(0);
        names.add(getString(R.string.SG_StudioFolderAll));
        for (org.telegram.messenger.MessagesController.DialogFilter f : getMessagesController().getDialogFilters()) {
            if (f.isDefault()) {
                continue;
            }
            ids.add(f.id);
            names.add(f.name);
        }
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_StudioFolder))
            .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                SolegramStudio.setFolderId(ids.get(which));
                cell.setTextAndValue(getString(R.string.SG_StudioFolder), names.get(which), true);
            })
            .create());
    }

    private LinearLayout panel(Context context) {
        final LinearLayout p = new LinearLayout(context);
        p.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        p.setBackground(bg);
        p.setClipToOutline(true);
        return p;
    }

    private TextView button(Context context, String label, boolean primary, Runnable r) {
        final TextView b = new TextView(context);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? 0xFFFFFFFF : accent());
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(primary ? accent() : Theme.multAlpha(accent(), 0.12f));
        b.setBackground(bg);
        b.setOnClickListener(v -> r.run());
        com.solegram.messenger.ui.SolegramMotion.pressable(b, com.solegram.messenger.ui.SolegramMotion.PRESS_CARD);
        return b;
    }

    private TextView small(Context context, String label, Runnable r) {
        final TextView b = button(context, label, false, r);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        return b;
    }

    // ------------------------------------------------------------------ running

    private void start(ArrayList<SolegramStudio.Scene> list, boolean record) {
        if (running || list.isEmpty() || getParentActivity() == null) {
            return;
        }
        // the Studio can't type the wallet's code: the person opens the wallet once, then starts again
        for (SolegramStudio.Scene s : list) {
            if (SolegramStudio.needsWallet(s) && !com.solegram.messenger.wallet.SoleWallet.isUnlocked(currentAccount)) {
                presentFragment(new com.solegram.messenger.wallet.SoleWalletActivity());
                AndroidUtilities.runOnUIThread(() -> {
                    final BaseFragment now = LaunchActivity.getSafeLastFragment();
                    if (now != null) {
                        BulletinFactory.of(now).createSimpleBulletin(R.raw.info, getString(R.string.SG_StudioUnlockWallet)).show();
                    }
                }, 400);
                // once the person has opened it, the Studio comes back and goes on by itself
                waitForWallet(list, record, android.os.SystemClock.elapsedRealtime() + 180_000);
                return;
            }
        }
        if (record && !StudioRecordService.ready()) {
            // Android asks the person once (the whole screen or one app); the scenes start when the recorder is ready
            StudioCaptureActivity.request(getParentActivity(), () -> run(list, true), this::showError);
            return;
        }
        run(list, record);
    }

    private void waitForWallet(ArrayList<SolegramStudio.Scene> list, boolean record, long until) {
        AndroidUtilities.runOnUIThread(() -> {
            if (isFinishing() || getParentActivity() == null || android.os.SystemClock.elapsedRealtime() > until) {
                return;
            }
            if (!com.solegram.messenger.wallet.SoleWallet.isUnlocked(currentAccount)) {
                waitForWallet(list, record, until);
                return;
            }
            // back to the Studio: close what lies above it (the wallet), then start
            for (int i = 0; i < 8; i++) {
                final BaseFragment top = LaunchActivity.getSafeLastFragment();
                if (top == null || top == this) {
                    break;
                }
                top.finishFragment(false);
            }
            AndroidUtilities.runOnUIThread(() -> start(list, record), 500);
        }, 500);
    }

    private void showError(String why) {
        final BaseFragment now = LaunchActivity.getSafeLastFragment();
        if (now != null) {
            BulletinFactory.of(now).createSimpleBulletin(R.raw.error, getString(R.string.SG_StudioRecordFailed), why).show();
        }
    }

    private void run(ArrayList<SolegramStudio.Scene> list, boolean record) {
        final Activity a = getParentActivity();
        if (a == null) {
            return;
        }
        running = true;
        SolegramStudio.setRunning(true);
        StudioRecordService.lastError = null;
        // a screen that hid itself from recordings when the run started lets the recording see it now
        a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        savedVideos = 0;
        savedShots = 0;
        final SolegramStudio.TouchMarks marks = showTouches ? SolegramStudio.TouchMarks.attach(a) : null;
        new Scenes(a, list, record, marks).next();
    }

    /** Plays the scenes one after another; each starts and ends on the Studio's screen. */
    private final class Scenes {
        private final Activity a;
        private final ArrayList<SolegramStudio.Scene> list;
        private final boolean record;
        private final SolegramStudio.TouchMarks marks;
        private final Handler h = new Handler(Looper.getMainLooper());
        private int scene = -1, step;

        Scenes(Activity a, ArrayList<SolegramStudio.Scene> list, boolean record, SolegramStudio.TouchMarks marks) {
            this.a = a;
            this.list = list;
            this.record = record;
            this.marks = marks;
        }

        void next() {
            scene++;
            if (scene >= list.size()) {
                finish();
                return;
            }
            step = 0;
            // every scene starts on the main screen, showing the chosen folder's chats
            SolegramStudio.home();
            h.postDelayed(() -> {
                if (record) {
                    StudioRecordService.beginSegment(list.get(scene).id);
                    // a still screen sends the recorder no frame: one redraw starts the video
                    a.getWindow().getDecorView().invalidate();
                }
                h.postDelayed(this::step, 400);
            }, 700);
        }

        private void step() {
            final JSONArray steps = list.get(scene).steps;
            if (step >= steps.length()) {
                endScene();
                return;
            }
            JSONObject s;
            try {
                s = steps.getJSONObject(step++);
            } catch (Exception e) {
                h.post(this::step);
                return;
            }
            long after = 250;
            try {
                if (s.has("open")) {
                    SolegramStudio.open(s.getString("open"));
                    after = 350;
                } else if (s.has("wait")) {
                    after = Math.max(0, Math.min(20_000, s.getLong("wait")));
                } else if (s.has("tap")) {
                    final JSONArray p = s.getJSONArray("tap");
                    SolegramStudio.tap(a, (float) p.getDouble(0), (float) p.getDouble(1), marks);
                    after = 300;
                } else if (s.has("swipe")) {
                    final JSONArray p = s.getJSONArray("swipe");
                    final long ms = p.length() > 4 ? Math.max(80, Math.min(4000, p.getLong(4))) : 500;
                    SolegramStudio.swipe(a, (float) p.getDouble(0), (float) p.getDouble(1), (float) p.getDouble(2), (float) p.getDouble(3), ms, marks);
                    after = ms + 200;
                } else if (s.has("back")) {
                    a.onBackPressed();
                    after = 450;
                } else if (s.has("shot")) {
                    final String name = s.getString("shot");
                    if (marks != null) {
                        marks.setVisibility(View.INVISIBLE);
                    }
                    SolegramStudio.shot(a, name, where -> {
                        if (marks != null) {
                            marks.setVisibility(View.VISIBLE);
                        }
                        if (where != null) {
                            savedShots++;
                        }
                        h.postDelayed(this::step, 150);
                    });
                    return;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            h.postDelayed(this::step, after);
        }

        private void endScene() {
            h.postDelayed(() -> {
                if (record && StudioRecordService.endSegment() != null) {
                    savedVideos++;
                }
                next();
            }, 400);
        }

        private void finish() {
            running = false;
            SolegramStudio.setRunning(false);
            SolegramStudio.stopOwnMusic();
            if (marks != null) {
                marks.detach();
            }
            if (record) {
                StudioRecordService.finish(a);
            }
            // the Studio comes back with what was saved
            SolegramStudio.home();
            final BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null) {
                last.presentFragment(new StudioActivity());
                AndroidUtilities.runOnUIThread(() -> {
                    final BaseFragment now = LaunchActivity.getSafeLastFragment();
                    if (now != null) {
                        final String err = StudioRecordService.lastError;
                        if (record && err != null) {
                            BulletinFactory.of(now).createSimpleBulletin(R.raw.error, formatString(R.string.SG_StudioDone, savedVideos, savedShots), err).show();
                        } else {
                            BulletinFactory.of(now).createSimpleBulletin(R.raw.done, formatString(R.string.SG_StudioDone, savedVideos, savedShots)).show();
                        }
                    }
                }, 500);
            }
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // a run goes on without this screen (scenes go back to the main screen); only an idle capture ends here
        if (!running && getParentActivity() != null && StudioRecordService.ready()) {
            StudioRecordService.finish(getParentActivity());
        }
    }

    /** Opens the Studio from anywhere. */
    public static void open(BaseFragment from) {
        if (from != null) {
            from.presentFragment(new StudioActivity());
        }
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }
}
