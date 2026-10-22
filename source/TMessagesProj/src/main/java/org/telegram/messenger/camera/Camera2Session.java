package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.solegram.messenger.SolegramCamera;
import com.solegram.messenger.SolegramPhotoEnhance;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float currentZoom = 1f;

    private final Size previewSize;

    private ImageReader imageReader;

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        return create(front, viewWidth, viewHeight, false);
    }

    /** Solegram: forPhoto - stills are taken at the sensor's full resolution, not at the preview size. */
    public static Camera2Session create(boolean front, int viewWidth, int viewHeight, boolean forPhoto) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size size = chooseOptimalSize(confMap.getOutputSizes(SurfaceTexture.class), viewWidth, viewHeight, false);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                } else {

                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Camera2Session(context, front, cameraId, bestSize, forPhoto);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size, boolean forPhoto) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    updateCaptureRequest();
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        // Solegram: a photo session takes its stills at the largest JPEG size of the preview's shape
        Size stillSize = size;
        if (forPhoto) {
            try {
                stillSize = chooseStillSize(cameraManager.getCameraCharacteristics(cameraId), size);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        this.imageReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 1);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();

        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        // TODO: support wide zoom camera switching
        return 1f;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    // Frame rate of recordings: an optional cap (two cameras at once), the range picked for this
    // camera with the rate it records at, and the rate recordings come out at.
    private volatile int fpsLimit;
    private volatile Range<Integer> videoFpsRange;
    private volatile int videoFpsRangeFps = 30;
    private volatile int recordingFps = 30;

    /** Caps the recording frame rate, e.g. while two cameras run at once. Call before the camera opens. */
    public void setFpsLimit(int fps) {
        fpsLimit = fps;
        videoFpsRange = null;
    }

    /** Frame rate the camera was set up to give recordings. */
    public int getRecordingFps() {
        return recordingFps;
    }

    private Range<Integer> getVideoFpsRange() {
        Range<Integer> range = videoFpsRange;
        if (range != null) {
            return range;
        }
        int target = SolegramCamera.targetFps();
        if (fpsLimit > 0) {
            target = Math.min(target, fpsLimit);
        }
        Range<Integer>[] ranges = null;
        try {
            ranges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            // A big preview size may not come out at the full rate; never ask for less than 30 because of it.
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                final long frameDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, previewSize);
                if (frameDuration > 0) {
                    target = Math.min(target, Math.max(30, (int) Math.round(1e9 / frameDuration)));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        range = SolegramCamera.pickCamera2Range(ranges, target, SolegramCamera.adaptive());
        if (range == null) {
            // Nothing above 30 (or 30 chosen): the range asked for before this setting, recorded as 30 fps like then.
            range = new Range<>(30, 60);
            videoFpsRangeFps = 30;
        } else {
            videoFpsRangeFps = SolegramCamera.maxFps(range);
        }
        videoFpsRange = range;
        return range;
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private void updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return;
        try {
            captureRequestBuilder = createRepeatingBuilder();
            // Solegram: while a still is being prepared the preview's results drive it
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), stillCapture, handler);
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
        }
    }

    // Solegram: the preview request, also the base of the requests that prepare a still.
    private CaptureRequest.Builder createRepeatingBuilder() throws CameraAccessException {
        final int template;
        if (recordingVideo) {
            template = CameraDevice.TEMPLATE_RECORD;
        } else if (scanningBarcode) {
            template = CameraDevice.TEMPLATE_STILL_CAPTURE;
        } else {
            template = CameraDevice.TEMPLATE_PREVIEW;
        }
        final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(template);

        if (scanningBarcode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
        } else if (nightMode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
        }

        builder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

        if (recordingVideo) {
            // The range for the frame rate chosen in settings.
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getVideoFpsRange());
            recordingFps = videoFpsRangeFps;
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
        }

        if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
            final int centerX = sensorSize.width() / 2;
            final int centerY = sensorSize.height() / 2;
            final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
            final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
            cropRegion.set(
                    centerX - deltaX,
                    centerY - deltaY,
                    centerX + deltaX,
                    centerY + deltaY
            );
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
        }

        builder.addTarget(surface);
        return builder;
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (cameraDevice == null || captureSession == null || imageReader == null || stillCapture != null) return false;
        try {
            final int orientation = getJpegOrientation();
            final StillCapture capture = new StillCapture(orientation, whenDone);
            // Solegram: the JPEG is taken off the camera thread, written and processed in the background;
            // the caller still hears back on the UI thread, also when no image came.
            imageReader.setOnImageAvailableListener(reader -> {
                byte[] bytes = null;
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
                reader.setOnImageAvailableListener(null, null);
                final byte[] data = bytes;
                SolegramPhotoEnhance.execute(() -> {
                    if (data != null) {
                        try (FileOutputStream output = new FileOutputStream(file)) {
                            output.write(data);
                        } catch (IOException e) {
                            FileLog.e(e);
                        }
                        final Bitmap enhanced = SolegramPhotoEnhance.enhanceFile(file);
                        if (enhanced != null) {
                            enhanced.recycle();
                        }
                    }
                    capture.deliver();
                });
            }, handler);
            stillCapture = capture;
            handler.post(capture::start);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            stillCapture = null;
            return false;
        }
    }

    // Solegram: a still taken the way the stock camera takes it: focus locked and exposure metered
    // for the shot first, then one capture in the camera's best quality modes, then the preview let
    // go again. Never waits longer than STILL_WAIT_MS for the camera to settle, it shoots anyway.
    private static final long STILL_WAIT_MS = 1000;
    private static final Object TRIGGER_TAG = new Object();
    private volatile StillCapture stillCapture;

    // Runs on the camera thread: its results come on the camera handler.
    private final class StillCapture extends CameraCaptureSession.CaptureCallback implements Runnable {
        private final int orientation;
        private final Utilities.Callback<Integer> whenDone;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private boolean lockFocus, meter, shot, sawPrecapture;
        private long triggerFrame = -1;

        StillCapture(int orientation, Utilities.Callback<Integer> whenDone) {
            this.orientation = orientation;
            this.whenDone = whenDone;
        }

        void start() {
            if (stillCapture != this) return;
            if (cameraDevice == null || captureSession == null || surface == null) {
                shoot();
                return;
            }
            lockFocus = canLockFocus();
            meter = canMeter();
            if (!lockFocus && !meter) {
                shoot();
                return;
            }
            handler.postDelayed(this, STILL_WAIT_MS);
            try {
                captureSession.setRepeatingRequest(createSequenceBuilder().build(), this, handler);
                final CaptureRequest.Builder trigger = createSequenceBuilder();
                if (lockFocus) {
                    trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                }
                if (meter) {
                    trigger.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                }
                trigger.setTag(TRIGGER_TAG);
                captureSession.capture(trigger.build(), this, handler);
            } catch (Exception e) {
                FileLog.e(e);
                shoot();
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            if (shot) return;
            if (request.getTag() == TRIGGER_TAG) {
                triggerFrame = result.getFrameNumber();
            }
            // results come in frame order: only the ones from the trigger on tell about it
            if (triggerFrame < 0 || result.getFrameNumber() < triggerFrame) return;
            if (focused(result) && metered(result)) {
                shoot();
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            if (!shot && request.getTag() == TRIGGER_TAG) {
                shoot();
            }
        }

        private boolean focused(CaptureResult result) {
            if (!lockFocus) return true;
            final Integer state = result.get(CaptureResult.CONTROL_AF_STATE);
            if (state == null || state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                return true;
            }
            // an AF that ignores the trigger stays inactive
            return state == CaptureResult.CONTROL_AF_STATE_INACTIVE && result.getFrameNumber() > triggerFrame;
        }

        private boolean metered(CaptureResult result) {
            if (!meter) return true;
            final Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
            if (state == null) return true;
            if (state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                sawPrecapture = true;
                return false;
            }
            if (state == CaptureResult.CONTROL_AE_STATE_SEARCHING) return false;
            // settled after the precapture ran, or the camera did not start one within a couple of frames
            return sawPrecapture || result.getFrameNumber() >= triggerFrame + 2;
        }

        @Override
        public void run() {
            shoot();
        }

        private void shoot() {
            if (shot) return;
            shot = true;
            handler.removeCallbacks(this);
            try {
                captureSession.capture(createStillBuilder(orientation).build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        release();
                    }

                    @Override
                    public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                        FileLog.e("Camera2Session still capture failed " + failure.getReason());
                        release();
                        if (!failure.wasImageCaptured()) {
                            deliver();
                        }
                    }
                }, handler);
            } catch (Exception e) {
                FileLog.e("Camera2Sessions takePicture error", e);
                release();
                deliver();
            }
        }

        // Lets focus and exposure go and puts the usual preview back.
        private void release() {
            if (stillCapture == this) {
                stillCapture = null;
            }
            if (cameraDevice == null || captureSession == null || surface == null) return;
            if (lockFocus || meter) {
                try {
                    final CaptureRequest.Builder unlock = createRepeatingBuilder();
                    if (lockFocus) {
                        unlock.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                    }
                    if (meter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        unlock.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
                    }
                    captureSession.capture(unlock.build(), null, handler);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            updateCaptureRequest();
        }

        void deliver() {
            if (delivered.compareAndSet(false, true)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (whenDone != null) {
                        whenDone.run(orientation);
                    }
                });
            }
        }
    }

    private boolean canLockFocus() {
        if (scanningBarcode || cameraCharacteristics == null) return false;
        try {
            final Float minFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocus == null || minFocus <= 0f) return false; // fixed focus
            final int[] modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            return contains(modes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) || contains(modes, CameraMetadata.CONTROL_AF_MODE_AUTO);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private boolean canMeter() {
        if (scanningBarcode || cameraCharacteristics == null) return false;
        try {
            return contains(cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CameraMetadata.CONTROL_AE_MODE_ON);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private boolean canFlashWithAe() {
        try {
            return cameraCharacteristics != null && contains(cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // The preview while a still is prepared: with flash on, exposure meters for the flash.
    private CaptureRequest.Builder createSequenceBuilder() throws CameraAccessException {
        final CaptureRequest.Builder builder = createRepeatingBuilder();
        if (flashing && canFlashWithAe()) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        }
        return builder;
    }

    private CaptureRequest.Builder createStillBuilder(int orientation) throws CameraAccessException {
        final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.addTarget(imageReader.getSurface());
        builder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        // the camera's slowest, best processing where it lists it
        setIfAvailable(builder, CaptureRequest.NOISE_REDUCTION_MODE, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        setIfAvailable(builder, CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
        setIfAvailable(builder, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        setIfAvailable(builder, CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY);
        setIfAvailable(builder, CaptureRequest.TONEMAP_MODE, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY);
        setIfAvailable(builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setIfAvailable(builder, CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_AVAILABLE_MODES, CameraMetadata.SHADING_MODE_HIGH_QUALITY);
        }
        if (scanningBarcode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
        } else if (nightMode) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
        }
        if (flashing) {
            if (canFlashWithAe()) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
            }
        }
        if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
            final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
            final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
            builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(sensorSize.centerX() - deltaX, sensorSize.centerY() - deltaY, sensorSize.centerX() + deltaX, sensorSize.centerY() + deltaY));
        }
        return builder;
    }

    private void setIfAvailable(CaptureRequest.Builder builder, CaptureRequest.Key<Integer> key, CameraCharacteristics.Key<int[]> available, int mode) {
        try {
            if (cameraCharacteristics != null && contains(cameraCharacteristics.get(available), mode)) {
                builder.set(key, mode);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) return false;
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    // Solegram: largest JPEG size with the preview's shape (capped like Camera1 photos), else the preview size as before.
    private static Size chooseStillSize(CameraCharacteristics characteristics, Size preview) {
        final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return preview;
        }
        final ArrayList<org.telegram.messenger.camera.Size> list = new ArrayList<>(sizes.length);
        for (Size size : sizes) {
            list.add(new org.telegram.messenger.camera.Size(size.getWidth(), size.getHeight()));
        }
        final org.telegram.messenger.camera.Size current = new org.telegram.messenger.camera.Size(preview.getWidth(), preview.getHeight());
        final org.telegram.messenger.camera.Size picked = SolegramCamera.pickPictureSize(list, current, current,
            SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW);
        return picked == current ? preview : new Size(picked.getWidth(), picked.getHeight());
    }

    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}