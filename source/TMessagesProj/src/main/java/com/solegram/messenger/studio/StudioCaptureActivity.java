/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.studio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

/**
 * Asks Android for the screen capture and takes its answer itself: a see-through screen of its own, so the answer
 * does not have to travel through Telegram's main window and its screens (where it got lost: after "Entire screen"
 * or "A single app" nothing happened). With the answer it starts the recorder while it is still in front, as Android
 * requires, and closes.
 */
public class StudioCaptureActivity extends Activity {

    private static final int REQUEST = 1;
    private static Runnable onStarted;
    private static Utilities.Callback<String> onFailed;
    private boolean asked;

    static void request(Context from, Runnable started, Utilities.Callback<String> failed) {
        onStarted = started;
        onFailed = failed;
        final Intent i = new Intent(from, StudioCaptureActivity.class);
        if (!(from instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            asked = savedInstanceState.getBoolean("asked");
        }
        if (asked) {
            return;
        }
        asked = true;
        try {
            final MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST);
        } catch (Exception e) {
            FileLog.e(e);
            fail(e.getClass().getSimpleName());
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("asked", asked);
    }

    private int resultCode;
    private Intent resultData;
    private boolean gotResult;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST) {
            return;
        }
        // the answer is kept until this screen is back in front (onResume): Android starts a screen-capture
        // service only for an app in the foreground
        this.resultCode = resultCode;
        this.resultData = data;
        gotResult = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!gotResult) {
            return;
        }
        gotResult = false;
        final Runnable started = onStarted;
        final Utilities.Callback<String> failed = onFailed;
        onStarted = null;
        onFailed = null;
        if (resultCode == RESULT_OK && resultData != null) {
            StudioRecordService.say(getString(org.telegram.messenger.R.string.SG_StudioAllowed));
            StudioRecordService.start(this, resultCode, resultData, started, failed);
            // the recorder answers within moments: when it does not, the person is told
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!StudioRecordService.ready() && StudioRecordService.lastError == null) {
                    StudioRecordService.say(org.telegram.messenger.ApplicationLoader.applicationContext.getString(org.telegram.messenger.R.string.SG_StudioRecordFailed) + ": no answer from the recorder");
                    if (failed != null) {
                        failed.run("no answer from the recorder");
                    }
                }
            }, 6000);
            // stays in front a moment longer, while the service takes the capture
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                finish();
                overridePendingTransition(0, 0);
            }, 400);
        } else {
            StudioRecordService.say(getString(org.telegram.messenger.R.string.SG_StudioNotAllowed) + " (" + resultCode + (resultData == null ? ", no data" : "") + ")");
            finish();
            overridePendingTransition(0, 0);
        }
        resultData = null;
    }

    private static void fail(String why) {
        final Utilities.Callback<String> f = onFailed;
        onStarted = null;
        onFailed = null;
        if (f != null) {
            f.run(why);
        }
    }
}
