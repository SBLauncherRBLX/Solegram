/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Display;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;

import org.telegram.messenger.Utilities;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.File;

/**
 * Solegram Studio's screen recorder. The person allows the capture once (Android's own dialog); the service then
 * keeps one virtual display of the screen and records it into one MP4 per scene, swapping the recorder under it.
 * Files go to Movies/Solegram Studio. It records only while the Studio runs a scene, and shows a notification all
 * the time it may record, as Android requires.
 */
public class StudioRecordService extends Service {

    private static final String CHANNEL = "solegram_studio";
    private static final int NOTIFICATION_ID = 0x5701;

    private static StudioRecordService instance;
    private static Runnable onReady;
    private static Utilities.Callback<String> onFailed;
    /** Why the last capture or scene video could not start (shown by the Studio), or null. */
    static String lastError;
    private static int consentCode;
    private static Intent consentData;

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor fd;
    private Uri current;
    private File currentFile;
    private int width, height, dpi, fps, bitrate;

    /** Whether the capture is allowed and running (scenes can be recorded). */
    public static boolean ready() {
        return instance != null && instance.projection != null && instance.display != null;
    }

    /** Starts the service with Android's capture permission; ready() is true once onStarted runs. */
    static void start(Context context, int resultCode, Intent data, Runnable onStarted, Utilities.Callback<String> failed) {
        onReady = onStarted;
        onFailed = failed;
        lastError = null;
        // the consent stays in this process: it is not sent through the system inside the start intent
        consentCode = resultCode;
        consentData = data;
        final Intent i = new Intent(context, StudioRecordService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            FileLog.e(e);
            fail(e.getClass().getSimpleName());
        }
    }

    private static void fail(String why) {
        lastError = why;
        say(org.telegram.messenger.ApplicationLoader.applicationContext.getString(org.telegram.messenger.R.string.SG_StudioRecordFailed) + ": " + why);
        final Utilities.Callback<String> f = onFailed;
        onFailed = null;
        onReady = null;
        if (f != null) {
            new Handler(Looper.getMainLooper()).post(() -> f.run(why));
        }
    }

    static void finish(Context context) {
        if (instance != null) {
            instance.stopSegment();
        }
        context.stopService(new Intent(context, StudioRecordService.class));
    }

    /** Starts a new MP4 for a scene. */
    static boolean beginSegment(String name) {
        return instance != null && instance.begin(name);
    }

    /** Ends the scene's MP4; returns where it is saved, or null. */
    @Nullable
    static String endSegment() {
        return instance == null ? null : instance.stopSegment();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        try {
            startForegroundCompat();
        } catch (Exception e) {
            // Android refused the capture service (it must start while the app is in front, after the consent)
            FileLog.e(e);
            fail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            stopSelf();
            return START_NOT_STICKY;
        }
        final int code = consentCode;
        final Intent data = consentData;
        consentData = null;
        if (data == null) {
            fail("no consent");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            final MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSegment();
                    if (display != null) {
                        display.release();
                        display = null;
                    }
                    projection = null;
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            // the whole screen with its system bars (the app's metrics leave the navigation bar out, and the mirror
            // then gets black bars), in a size and frame rate this phone's encoder really takes
            final DisplayMetrics m = new DisplayMetrics();
            final Display d = ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            d.getRealMetrics(m);
            pickFormat(m.widthPixels, m.heightPixels);
            dpi = m.densityDpi;
            display = projection.createVirtualDisplay("Solegram Studio", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null);
            if (display == null) {
                throw new IllegalStateException("no virtual display");
            }
            final Runnable r = onReady;
            onReady = null;
            onFailed = null;
            say(getString(org.telegram.messenger.R.string.SG_StudioReady) + " · " + width + "×" + height + " · " + fps + " fps");
            if (r != null) {
                new Handler(Looper.getMainLooper()).post(r);
            }
        } catch (Exception e) {
            FileLog.e(e);
            fail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /**
     * The video's size, frame rate and bit rate: at most 1080 wide, both sides multiples of 16 (some encoders take
     * nothing else), 60 fps where the H.264 encoder can do it at that size, else 30; smaller when it can't at all.
     * A tall screen at 1080 × 2400 and 60 fps is beyond many phones' encoders, and then recording never started.
     */
    private void pickFormat(int realW, int realH) {
        final MediaCodecInfo.VideoCapabilities caps = avcCaps();
        for (int target : new int[]{1080, 720, 540}) {
            final float s = Math.min(1f, target / (float) Math.min(realW, realH));
            final int w = Math.max(16, ((int) (realW * s)) & ~15), h = Math.max(16, ((int) (realH * s)) & ~15);
            for (int f : new int[]{60, 30}) {
                if (caps == null || caps.areSizeAndRateSupported(w, h, f)) {
                    width = w;
                    height = h;
                    fps = f;
                    int bits = (int) Math.min(16_000_000L, (long) w * h * f / 8);
                    if (caps != null) {
                        bits = caps.getBitrateRange().clamp(bits);
                    }
                    bitrate = bits;
                    return;
                }
            }
        }
        width = 720;
        height = ((int) (realH * 720f / realW)) & ~15;
        fps = 30;
        bitrate = 6_000_000;
    }

    @Nullable
    private static MediaCodecInfo.VideoCapabilities avcCaps() {
        try {
            MediaCodecInfo.VideoCapabilities soft = null;
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                        continue;
                    }
                    final MediaCodecInfo.VideoCapabilities c = info.getCapabilitiesForType(type).getVideoCapabilities();
                    final String name = info.getName().toLowerCase();
                    final boolean software = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly() : name.startsWith("omx.google.") || name.startsWith("c2.android.");
                    if (!software) {
                        return c; // MediaRecorder uses the phone's hardware encoder
                    }
                    if (soft == null) {
                        soft = c;
                    }
                }
            }
            return soft;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** A short note on the screen, whatever is in front (the Studio's own screen may be covered then). */
    static void say(String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignore) {
            }
        });
    }

    private void startForegroundCompat() {
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Solegram Studio", NotificationManager.IMPORTANCE_LOW));
        }
        final Notification n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle("Solegram Studio")
            .setContentText(getString(R.string.SG_StudioRecordingNote))
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private boolean begin(String name) {
        if (projection == null || display == null) {
            return false;
        }
        stopSegment();
        try {
            final String file = "Solegram-" + name + "-" + System.currentTimeMillis() + ".mp4";
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate(bitrate);
            if (Build.VERSION.SDK_INT >= 29) {
                final ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, file);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Solegram Studio");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                current = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                fd = getContentResolver().openFileDescriptor(current, "rw");
                recorder.setOutputFile(fd.getFileDescriptor());
            } else {
                final File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Solegram Studio");
                dir.mkdirs();
                currentFile = new File(dir, file);
                recorder.setOutputFile(currentFile.getAbsolutePath());
            }
            recorder.prepare();
            display.setSurface(recorder.getSurface());
            recorder.start();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "") + " · " + width + "×" + height + " " + fps + " fps";
            if (display != null) {
                display.setSurface(null);
            }
            releaseRecorder(true);
            return false;
        }
    }

    @Nullable
    private String stopSegment() {
        if (recorder == null) {
            return null;
        }
        String saved = null;
        // the screen stops feeding the encoder first, then the file is closed
        if (display != null) {
            display.setSurface(null);
        }
        boolean broken = false;
        try {
            recorder.stop();
            saved = current != null ? current.toString() : currentFile != null ? currentFile.getAbsolutePath() : null;
        } catch (Exception e) {
            // no frame reached the encoder (or it failed): the file is empty, it is not kept
            FileLog.e(e);
            lastError = "stop: " + e.getClass().getSimpleName();
            broken = true;
        }
        releaseRecorder(broken);
        return saved;
    }

    private void releaseRecorder(boolean discard) {
        try {
            if (recorder != null) {
                recorder.release();
            }
        } catch (Exception ignore) {
        }
        recorder = null;
        try {
            if (fd != null) {
                fd.close();
            }
        } catch (Exception ignore) {
        }
        fd = null;
        if (current != null && Build.VERSION.SDK_INT >= 29) {
            try {
                if (discard) {
                    getContentResolver().delete(current, null, null);
                } else {
                    final ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(current, v, null, null);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else if (discard && currentFile != null) {
            currentFile.delete();
        }
        current = null;
        currentFile = null;
    }

    @Override
    public void onDestroy() {
        stopSegment();
        if (display != null) {
            display.release();
            display = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
