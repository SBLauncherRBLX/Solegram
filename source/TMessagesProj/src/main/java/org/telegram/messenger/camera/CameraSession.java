/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import com.solegram.messenger.SolegramCamera;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

public class CameraSession {

    public CameraInfo cameraInfo;
    private String currentFlashMode;
    private OrientationEventListener orientationEventListener;
    private int lastOrientation = -1;
    private int lastDisplayOrientation = -1;
    private boolean isVideo;
    private final Size pictureSize;
    private final Size previewSize;
    private final int pictureFormat;
    private boolean initied;
    private int maxZoom;
    private boolean meteringAreaSupported;
    private int currentOrientation;
    private int diffOrientation;
    private int jpegOrientation;
    private boolean sameTakePictureOrientation;
    private boolean flipFront = true;
    private float currentZoom;
    private boolean optimizeForBarcode;
    private boolean useTorch;
    private boolean isRound;
    private boolean destroyed;

    // Frame rate of recordings: the camera's own range (kept once this session changes it, to go
    // back to), whether the camera refused a higher one, whether a higher one is asked for now, the
    // camera watched for errors meanwhile, and the rate recordings come out at.
    private int[] cameraFpsRange;
    private volatile boolean fpsRangeRefused;
    private volatile boolean fpsRaised;
    private Camera fpsWatchedCamera;
    private volatile boolean dualCamera;
    private volatile int recordingFps = 30;
    // Cameras (by id) that failed while raised: left at their own range until the app restarts.
    private static volatile int fpsFailedCameras;

    public ArrayList<String> availableFlashModes = new ArrayList<>();

    // Solegram camera controls: the torch the camera offers besides off / auto / on, the exposure
    // compensation picked on the viewfinder (in the camera's steps, kept while this session lives),
    // the zoom ratios the camera lists, and the shape the photos taken now are cut to (long side
    // over short side, 0 = as the camera gives them).
    public volatile boolean torchSupported;
    public volatile float solegramCropRatio;
    private volatile int exposureIndex;
    private volatile int minExposure, maxExposure;
    private volatile float exposureStep;
    private volatile List<Integer> zoomRatios;

    private int infoCameraId = -1;
    Camera.CameraInfo info = new Camera.CameraInfo();

    public static final int ORIENTATION_HYSTERESIS = 5;

    private Camera.AutoFocusCallback autoFocusCallback = (success, camera) -> {
        if (success) {

        } else {

        }
    };
    private int displayOrientation;

    public CameraSession(CameraInfo info, Size preview, Size picture, int format, boolean round) {
        previewSize = preview;
        pictureSize = picture;
        pictureFormat = format;
        cameraInfo = info;
        isRound = round;

        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
        currentFlashMode = sharedPreferences.getString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", Camera.Parameters.FLASH_MODE_OFF);

        orientationEventListener = new OrientationEventListener(ApplicationLoader.applicationContext) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientationEventListener == null || !initied || orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
                jpegOrientation = roundOrientation(orientation, jpegOrientation);
                WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
                int rotation = mgr.getDefaultDisplay().getRotation();
                if (lastOrientation != jpegOrientation || rotation != lastDisplayOrientation) {
                    if (!isVideo) {
                        configurePhotoCamera();
                    }
                    lastDisplayOrientation = rotation;
                    lastOrientation = jpegOrientation;
                }
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        } else {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }

    private void updateCameraInfo() {
        if (infoCameraId != cameraInfo.getCameraId()) {
            Camera.getCameraInfo(infoCameraId = cameraInfo.getCameraId(), this.info);
        }
    }

    private int roundOrientation(int orientation, int orientationHistory) {
        boolean changeOrientation;
        if (orientationHistory == OrientationEventListener.ORIENTATION_UNKNOWN) {
            changeOrientation = true;
        } else {
            int dist = Math.abs(orientation - orientationHistory);
            dist = Math.min(dist, 360 - dist);
            changeOrientation = (dist >= 45 + ORIENTATION_HYSTERESIS);
        }
        if (changeOrientation) {
            return ((orientation + 45) / 90 * 90) % 360;
        }
        return orientationHistory;
    }

    public void setOptimizeForBarcode(boolean value) {
        optimizeForBarcode = value;
        configurePhotoCamera();
    }

    public void checkFlashMode(String mode) {
        ArrayList<String> modes = availableFlashModes;
        if (modes.contains(currentFlashMode)) {
            return;
        }
        currentFlashMode = mode;
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
            sharedPreferences.edit().putString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
        }
    }

    public void setCurrentFlashMode(String mode) {
        currentFlashMode = mode;
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("camera", Activity.MODE_PRIVATE);
            sharedPreferences.edit().putString(cameraInfo.frontCamera != 0 ? "flashMode_front" : "flashMode", mode).commit();
        }
    }

    public void setTorchEnabled(boolean enabled) {
        try {
            String beforeFlashMode = currentFlashMode;
            currentFlashMode = enabled ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF;
            if (!TextUtils.equals(beforeFlashMode, currentFlashMode)) {
                if (isRound) {
                    configureRoundCamera(false);
                } else {
                    configurePhotoCamera();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public String getCurrentFlashMode() {
        return currentFlashMode;
    }

    public String getNextFlashMode() {
        ArrayList<String> modes = availableFlashModes;
        for (int a = 0; a < modes.size(); a++) {
            String mode = modes.get(a);
            if (mode.equals(currentFlashMode)) {
                if (a < modes.size() - 1) {
                    return modes.get(a + 1);
                } else {
                    return modes.get(0);
                }
            }
        }
        return currentFlashMode;
    }

    public void setInitied() {
        initied = true;
    }

    public boolean isInitied() {
        return initied;
    }

    public int getCurrentOrientation() {
        return currentOrientation;
    }

    public boolean isFlipFront() {
        return flipFront;
    }

    public void setFlipFront(boolean value) {
        flipFront = value;
    }

    public int getWorldAngle() {
        return diffOrientation;
    }

    public boolean isSameTakePictureOrientation() {
        return sameTakePictureOrientation;
    }

    protected boolean configureRoundCamera(boolean initial) {
        try {
            isVideo = true;
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                updateCameraInfo();
                updateRotation();

                if (params != null) {
                    if (initial && BuildVars.LOGS_ENABLED) {
                        FileLog.d("set preview size = " + previewSize.getWidth() + " " + previewSize.getHeight());
                    }
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    if (initial && BuildVars.LOGS_ENABLED) {
                        FileLog.d("set picture size = " + pictureSize.getWidth() + " " + pictureSize.getHeight());
                    }
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setRecordingHint(true);
                    final int[] fpsBefore = updateFpsRange(params, true);
                    maxZoom = params.getMaxZoom();

                    String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                    if (params.getSupportedFocusModes().contains(desiredMode)) {
                        params.setFocusMode(desiredMode);
                    } else {
                        desiredMode = Camera.Parameters.FOCUS_MODE_AUTO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(currentFlashMode);
                    params.setZoom((int) (currentZoom * maxZoom));
                    readSolegramCaps(params);
                    try {
                        setParameters(camera, params, fpsBefore);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                        //
                    }

                    if (params.getMaxNumMeteringAreas() > 0) {
                        meteringAreaSupported = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    public void updateRotation() {
        if (cameraInfo == null) {
            return;
        }

        try {
            updateCameraInfo();
        } catch (Throwable throwable) {
            FileLog.e(throwable);
            return;
        }
        Camera camera = destroyed ? null : cameraInfo.camera;

        displayOrientation = getDisplayOrientation(info, true);
        int cameraDisplayOrientation;

        int degrees = 0;
        if ("samsung".equals(Build.MANUFACTURER) && "sf2wifixx".equals(Build.PRODUCT)) {
            cameraDisplayOrientation = 0;
        } else {
            int temp = displayOrientation;
            switch (temp) {
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
            if (info.orientation % 90 != 0) {
                info.orientation = 0;
            }
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                temp = (info.orientation + degrees) % 360;
                temp = (360 - temp) % 360;
            } else {
                temp = (info.orientation - degrees + 360) % 360;
            }
            cameraDisplayOrientation = temp;
        }
        currentOrientation = cameraDisplayOrientation;
        if (camera != null) {
            try {
                camera.setDisplayOrientation(currentOrientation);
            } catch (Throwable ignore) {}
        }
        diffOrientation = currentOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
    }

    protected void configurePhotoCamera() {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                Camera.Parameters params = null;
                try {
                    params = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                updateCameraInfo();
                updateRotation();

                diffOrientation = currentOrientation - displayOrientation;
                if (diffOrientation < 0) {
                    diffOrientation += 360;
                }

                if (params != null) {
                    params.setPreviewSize(previewSize.getWidth(), previewSize.getHeight());
                    params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
                    params.setPictureFormat(pictureFormat);
                    params.setJpegQuality(100);
                    params.setJpegThumbnailQuality(100);
                    maxZoom = params.getMaxZoom();
                    params.setZoom((int) (currentZoom * maxZoom));

                    if (optimizeForBarcode) {
                        List<String> modes = params.getSupportedSceneModes();
                        if (modes != null && modes.contains(Camera.Parameters.SCENE_MODE_BARCODE)) {
                            params.setSceneMode(Camera.Parameters.SCENE_MODE_BARCODE);
                        }
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    } else {
                        String desiredMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
                        if (params.getSupportedFocusModes().contains(desiredMode)) {
                            params.setFocusMode(desiredMode);
                        }
                    }

                    int outputOrientation = 0;
                    if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
                        } else {
                            outputOrientation = (info.orientation + jpegOrientation) % 360;
                        }
                    }
                    try {
                        params.setRotation(outputOrientation);
                        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                            sameTakePictureOrientation = (360 - displayOrientation) % 360 == outputOrientation;
                        } else {
                            sameTakePictureOrientation = displayOrientation == outputOrientation;
                        }
                    } catch (Exception e) {
                        //
                    }
                    params.setFlashMode(useTorch ? Camera.Parameters.FLASH_MODE_TORCH : currentFlashMode);
                    readSolegramCaps(params);
                    // The higher frame rate is only for the time of a recording, so photos keep their usual exposure.
                    final int[] fpsBefore = updateFpsRange(params, isVideo && wantsHighFps());

                    try {
                        setParameters(camera, params, fpsBefore);
                    } catch (Exception e) {
                        //
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void focusToRect(Rect focusRect, Rect meteringRect) {
        try {
            Camera camera = cameraInfo.camera;
            if (camera != null) {
                camera.cancelAutoFocus();
                Camera.Parameters parameters = null;
                try {
                    parameters = camera.getParameters();
                } catch (Exception e) {
                    FileLog.e(e);
                }

                if (parameters != null) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    ArrayList<Camera.Area> meteringAreas = new ArrayList<>();
                    meteringAreas.add(new Camera.Area(focusRect, 1000));
                    parameters.setFocusAreas(meteringAreas);

                    if (meteringAreaSupported) {
                        meteringAreas = new ArrayList<>();
                        meteringAreas.add(new Camera.Area(meteringRect, 1000));
                        parameters.setMeteringAreas(meteringAreas);
                    }

                    try {
                        camera.setParameters(parameters);
                        camera.autoFocus(autoFocusCallback);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    protected int getMaxZoom() {
        return maxZoom;
    }

    public void onStartRecord() {
        isVideo = true;
        if (!isRound) {
            applyRecordingFps();
        }
    }

    /** Two cameras run at once (stories): both stay at the usual frame rate. */
    public void setDualCamera(boolean value) {
        dualCamera = value;
    }

    /** Frame rate the camera was set up to give recordings; 30 when it was left as it is. */
    public int getRecordingFps() {
        return recordingFps;
    }

    private boolean wantsHighFps() {
        return !optimizeForBarcode && !dualCamera;
    }

    // Raises the frame rate right as a recording starts, touching nothing else.
    private void applyRecordingFps() {
        try {
            Camera camera = cameraInfo.camera;
            if (camera == null) {
                return;
            }
            Camera.Parameters params = camera.getParameters();
            final int[] fpsBefore = updateFpsRange(params, wantsHighFps());
            if (fpsBefore != null) {
                setParameters(camera, params, fpsBefore);
            } else {
                updateRecordingFps(camera);
            }
            if (fpsRaised) {
                watchFpsErrors(camera);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // Many cameras list 60 fps for the old API but only manage it up to 720p.
    private boolean previewFitsHighFps() {
        if (previewSize == null) {
            return false;
        }
        final int w = previewSize.getWidth(), h = previewSize.getHeight();
        return Math.max(w, h) <= 1280 && Math.min(w, h) <= 720;
    }

    private boolean fpsFailedBefore() {
        final int id = cameraInfo.cameraId;
        return id >= 0 && id < 32 && (fpsFailedCameras & (1 << id)) != 0;
    }

    /**
     * Puts the frame rate the session needs now into params: the highest the setting and the
     * camera allow, or else the camera's own range back if this session changed it. Returns the
     * range params had before when it changed it, null when it left params alone.
     */
    private int[] updateFpsRange(Camera.Parameters params, boolean highFps) {
        try {
            int[] wanted = null;
            if (highFps && !fpsRangeRefused && previewFitsHighFps() && !fpsFailedBefore()) {
                wanted = SolegramCamera.pickCamera1Range(params.getSupportedPreviewFpsRange(), SolegramCamera.targetFps(), SolegramCamera.adaptive());
            }
            fpsRaised = wanted != null;
            if (wanted == null) {
                recordingFps = 30;
                wanted = cameraFpsRange;
            }
            if (wanted == null) {
                return null;
            }
            final int[] current = new int[2];
            params.getPreviewFpsRange(current);
            if (current[0] == wanted[0] && current[1] == wanted[1]) {
                return null;
            }
            if (cameraFpsRange == null) {
                cameraFpsRange = current;
            }
            params.setPreviewFpsRange(wanted[0], wanted[1]);
            return current;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // Some cameras list a frame rate and then refuse it: apply the rest with the old one.
    private void setParameters(Camera camera, Camera.Parameters params, int[] fpsBefore) {
        try {
            try {
                camera.setParameters(params);
            } catch (RuntimeException e) {
                if (fpsBefore == null) {
                    throw e;
                }
                FileLog.e(e);
                params.setPreviewFpsRange(fpsBefore[0], fpsBefore[1]);
                camera.setParameters(params);
                if (fpsRaised) {
                    // Only now is it clear the range was the problem: the rest went through without it.
                    fpsRangeRefused = true;
                    fpsRaised = false;
                }
            }
        } finally {
            updateRecordingFps(camera);
        }
    }

    // Recordings are told what the camera reports it runs at, or 30 as before when not raised.
    private void updateRecordingFps(Camera camera) {
        int fps = 30;
        if (fpsRaised) {
            try {
                final int[] range = new int[2];
                camera.getParameters().getPreviewFpsRange(range);
                if (range[1] > 30000) {
                    fps = range[1] / 1000;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        recordingFps = fps;
    }

    // An error while raised puts the camera's own range back where the camera still takes it, and
    // keeps this camera from being raised again; the error then goes on to the usual listener.
    private void watchFpsErrors(Camera camera) {
        if (fpsWatchedCamera == camera) {
            return;
        }
        fpsWatchedCamera = camera;
        final Camera.ErrorCallback listener = CameraController.getInstance().getErrorListener(this);
        camera.setErrorCallback((error, errorCamera) -> {
            // not when another app took the camera or a policy turned it off
            if (fpsRaised && (error == Camera.CAMERA_ERROR_UNKNOWN || error == Camera.CAMERA_ERROR_SERVER_DIED)) {
                fpsRangeRefused = true;
                final int id = cameraInfo.cameraId;
                if (id >= 0 && id < 32) {
                    fpsFailedCameras |= 1 << id;
                }
                try {
                    Camera.Parameters params = errorCamera.getParameters();
                    if (updateFpsRange(params, false) != null) {
                        errorCamera.setParameters(params);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                recordingFps = 30;
            }
            listener.onError(error, errorCamera);
        });
    }

    public void setZoom(float value) {
        currentZoom = value;
        if (isVideo && Camera.Parameters.FLASH_MODE_ON.equals(currentFlashMode)) {
            useTorch = true;
        }
        if (isRound) {
            configureRoundCamera(false);
        } else {
            configurePhotoCamera();
        }
    }

    // region Solegram camera controls

    // What the camera offers for exposure and zoom, read whenever the parameters are set up; the
    // exposure picked on the viewfinder goes in with them, so a zoom or rotation keeps it.
    private void readSolegramCaps(Camera.Parameters params) {
        try {
            final int min = params.getMinExposureCompensation(), max = params.getMaxExposureCompensation();
            final float step = params.getExposureCompensationStep();
            minExposure = min;
            maxExposure = max;
            exposureStep = step;
            if (min < max && step > 0) {
                params.setExposureCompensation(Math.max(min, Math.min(max, exposureIndex)));
            }
            zoomRatios = params.isZoomSupported() ? params.getZoomRatios() : null;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The camera can shift its exposure (both ways). */
    public boolean isExposureSupported() {
        return minExposure < 0 && maxExposure > 0 && exposureStep > 0;
    }

    public int getMinExposure() {
        return minExposure;
    }

    public int getMaxExposure() {
        return maxExposure;
    }

    /** Size of one exposure step in EV. */
    public float getExposureStep() {
        return exposureStep;
    }

    public int getExposureIndex() {
        return exposureIndex;
    }

    /**
     * Exposure compensation in the camera's own steps (0 = as the camera meters). Only this
     * parameter is sent, so dragging the slider stays light. Call on the main thread.
     */
    public void setExposureIndex(int index) {
        if (!isExposureSupported()) {
            return;
        }
        index = Math.max(minExposure, Math.min(maxExposure, index));
        if (index == exposureIndex) {
            return;
        }
        exposureIndex = index;
        try {
            final Camera camera = destroyed ? null : cameraInfo.camera;
            if (camera != null) {
                final Camera.Parameters params = camera.getParameters();
                params.setExposureCompensation(index);
                camera.setParameters(params);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Largest zoom the camera gives, as a ratio (2f = 2x); 1 when it can't zoom. */
    public float getMaxZoomRatio() {
        final List<Integer> ratios = zoomRatios;
        if (ratios == null || ratios.isEmpty()) {
            return 1f;
        }
        return ratios.get(ratios.size() - 1) / 100f;
    }

    /** Zoom ratio of a zoom value (0..1, the value {@link #setZoom} takes). */
    public float zoomRatio(float zoom) {
        final List<Integer> ratios = zoomRatios;
        if (ratios == null || ratios.isEmpty() || maxZoom <= 0) {
            return 1f;
        }
        final int index = Math.max(0, Math.min(ratios.size() - 1, (int) (zoom * maxZoom)));
        return ratios.get(index) / 100f;
    }

    /** Zoom value (0..1) of the first zoom step that reaches the ratio. */
    public float zoomForRatio(float ratio) {
        final List<Integer> ratios = zoomRatios;
        if (ratios == null || ratios.isEmpty() || maxZoom <= 0) {
            return 0f;
        }
        final int wanted = Math.round(ratio * 100);
        for (int i = 0; i < ratios.size(); i++) {
            if (ratios.get(i) >= wanted) {
                // the value lands on this step after (int) (zoom * maxZoom)
                return i == 0 ? 0f : Math.min(1f, (i + 0.5f) / maxZoom);
            }
        }
        return 1f;
    }
    // endregion

    protected void configureRecorder(int quality, MediaRecorder recorder) {
        updateCameraInfo();

        int outputOrientation = 0;
        if (jpegOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                outputOrientation = (info.orientation - jpegOrientation + 360) % 360;
            } else {
                outputOrientation = (info.orientation + jpegOrientation) % 360;
            }
        }
        recorder.setOrientationHint(outputOrientation);

        int highProfile = getHigh();
        boolean canGoHigh = CamcorderProfile.hasProfile(cameraInfo.cameraId, highProfile);
        boolean canGoLow = CamcorderProfile.hasProfile(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW);
        if (canGoHigh && (quality == 1 || !canGoLow)) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, highProfile));
        } else if (canGoLow) {
            recorder.setProfile(CamcorderProfile.get(cameraInfo.cameraId, CamcorderProfile.QUALITY_LOW));
        } else {
            throw new IllegalStateException("cannot find valid CamcorderProfile");
        }
        isVideo = true;
    }

    public void stopVideoRecording() {
        isVideo = false;
        useTorch = false;
        configurePhotoCamera();
    }

    private int getHigh() {
        if ("LGE".equals(Build.MANUFACTURER) && "g3_tmo_us".equals(Build.PRODUCT)) {
            return CamcorderProfile.QUALITY_480P;
        }
        return CamcorderProfile.QUALITY_HIGH;
    }

    private int getDisplayOrientation(Camera.CameraInfo info, boolean isStillCapture) {
        WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation = mgr.getDefaultDisplay().getRotation();
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

        int displayOrientation;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            displayOrientation = (info.orientation + degrees) % 360;
            displayOrientation = (360 - displayOrientation) % 360;

            if (!isStillCapture && displayOrientation == 90) {
                displayOrientation = 270;
            }
            if (!isStillCapture && "Huawei".equals(Build.MANUFACTURER) && "angler".equals(Build.PRODUCT) && displayOrientation == 270) {
                displayOrientation = 90;
            }
        } else {
            displayOrientation = (info.orientation - degrees + 360) % 360;
        }

        return displayOrientation;
    }

    public int getDisplayOrientation() {
        try {
            updateCameraInfo();
            return getDisplayOrientation(info, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public void setPreviewCallback(Camera.PreviewCallback callback){
        cameraInfo.camera.setPreviewCallback(callback);
    }

    public void setOneShotPreviewCallback(Camera.PreviewCallback callback) {
        if (cameraInfo != null && cameraInfo.camera != null) {
            try {
                cameraInfo.camera.setOneShotPreviewCallback(callback);
            } catch (Exception ignore) {

            }
        }
    }

    public void destroy() {
        initied = false;
        destroyed = true;
        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
    }

    public Camera.Size getCurrentPreviewSize() {
        return cameraInfo.camera.getParameters().getPreviewSize();
    }

    public Camera.Size getCurrentPictureSize() {
        return cameraInfo.camera.getParameters().getPictureSize();
    }
}
