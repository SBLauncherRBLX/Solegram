/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.util.Range;

import org.telegram.messenger.camera.Size;

import java.util.List;

/**
 * Frame rate of recorded videos (attach camera, stories, round videos) and the size photos are
 * taken at. Only pure helpers: the camera classes pass in what the device offers and get back
 * what to ask for.
 */
public final class SolegramCamera {

    /** Above this the extra bitrate stops buying anything for phone-sized video. */
    private static final int MAX_BITRATE = 20_000_000;
    // "Maximum" lets the camera slow down to this in the dark; below it motion starts to stutter.
    private static final int ADAPTIVE_MIN_FPS = 24;

    // Longest photo side asked of the camera: 12 MP class. Bigger sizes (50-200 MP sensors) only
    // cost decode time and memory, photos are sent at 2560 at most.
    private static final int MAX_PHOTO_SIDE = 4096;
    private static final int MAX_PHOTO_SIDE_LOW_END = 2600;

    private SolegramCamera() {
    }

    /**
     * Largest photo size with the preview's shape, up to 12 MP or so, so the downscale to the
     * size that is sent starts from all the detail the sensor gives. The given size when there
     * is nothing bigger of that shape.
     */
    public static Size pickPictureSize(List<Size> sizes, Size aspectRatio, Size current, boolean lowEnd) {
        if (sizes == null || aspectRatio == null || aspectRatio.getWidth() <= 0 || aspectRatio.getHeight() <= 0) {
            return current;
        }
        final int maxSide = lowEnd ? MAX_PHOTO_SIDE_LOW_END : MAX_PHOTO_SIDE;
        final float shape = (float) Math.max(aspectRatio.getWidth(), aspectRatio.getHeight()) / Math.min(aspectRatio.getWidth(), aspectRatio.getHeight());
        Size best = null;
        long bestArea = current == null ? 0 : (long) current.getWidth() * current.getHeight();
        for (int i = 0; i < sizes.size(); i++) {
            final Size size = sizes.get(i);
            if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
                continue;
            }
            final int longSide = Math.max(size.getWidth(), size.getHeight());
            final int shortSide = Math.min(size.getWidth(), size.getHeight());
            if (longSide > maxSide || Math.abs((float) longSide / shortSide - shape) > 0.01f) {
                continue;
            }
            final long area = (long) size.getWidth() * size.getHeight();
            if (area > bestArea) {
                best = size;
                bestArea = area;
            }
        }
        return best != null ? best : current;
    }

    /** Frame rate the user wants: 30 leaves the camera as before this setting, otherwise up to 60. */
    public static int targetFps() {
        return SolegramConfig.cameraFps == 30 ? 30 : 60;
    }

    /**
     * "Maximum" (as opposed to a fixed 60): a range the camera can slow down in, so a dark scene
     * gets longer exposures instead of a dark picture.
     */
    public static boolean adaptive() {
        return SolegramConfig.cameraFps != 30 && SolegramConfig.cameraFps != 60;
    }

    /**
     * Preview fps range for the old camera API (values are fps x 1000), or null to leave the
     * camera as it is. Only a range going above 30 is worth asking for: the camera gives 30 on its
     * own, and one that tops out there must behave exactly as before.
     */
    public static int[] pickCamera1Range(List<int[]> supported, int targetFps, boolean adaptive) {
        if (supported == null || supported.isEmpty()) {
            return null;
        }
        final int count = supported.size();
        final int[] lower = new int[count];
        final int[] upper = new int[count];
        for (int i = 0; i < count; i++) {
            final int[] range = supported.get(i);
            if (range == null || range.length < 2) {
                lower[i] = upper[i] = -1;
            } else {
                lower[i] = range[0];
                upper[i] = range[1];
            }
        }
        final int index = pick(lower, upper, targetFps, adaptive);
        return index < 0 ? null : new int[] {lower[index], upper[index]};
    }

    /**
     * AE target fps range for Camera2, always one the camera lists, or null when none goes above
     * 30: the caller then asks for what it asked before this setting existed.
     */
    public static Range<Integer> pickCamera2Range(Range<Integer>[] ranges, int targetFps, boolean adaptive) {
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        final int[] lower = new int[ranges.length];
        final int[] upper = new int[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            final Range<Integer> range = ranges[i];
            if (range == null) {
                lower[i] = upper[i] = -1;
            } else {
                lower[i] = fps(range.getLower()) * 1000;
                upper[i] = fps(range.getUpper()) * 1000;
            }
        }
        final int index = pick(lower, upper, targetFps, adaptive);
        return index < 0 ? null : ranges[index];
    }

    /**
     * Index of the range to ask for (values in fps x 1000), -1 when none goes above 30. The
     * highest top not above the target wins. Among equal tops a steady rate takes the narrowest
     * (60-60); an adaptive one the widest that still keeps 24 fps (30-60 rather than 60-60, not
     * 15-60), or the narrowest when there is none.
     */
    private static int pick(int[] lower, int[] upper, int targetFps, boolean adaptive) {
        final int limit = targetFps * 1000;
        int top = 0;
        for (int i = 0; i < upper.length; i++) {
            if (lower[i] >= 0 && lower[i] <= upper[i] && upper[i] <= limit && upper[i] > top) {
                top = upper[i];
            }
        }
        if (top <= 30000) {
            return -1;
        }
        int narrowest = -1;
        int widest = -1;
        for (int i = 0; i < upper.length; i++) {
            if (upper[i] != top || lower[i] < 0 || lower[i] > top) {
                continue;
            }
            if (narrowest < 0 || lower[i] > lower[narrowest]) {
                narrowest = i;
            }
            if (lower[i] >= ADAPTIVE_MIN_FPS * 1000 && (widest < 0 || lower[i] < lower[widest])) {
                widest = i;
            }
        }
        return adaptive && widest >= 0 ? widest : narrowest;
    }

    /** Top frame rate of a Camera2 range. */
    public static int maxFps(Range<Integer> range) {
        return range == null ? 30 : fps(range.getUpper());
    }

    /** More frames need more bits for the same picture: half as much again above 30 fps. */
    public static int bitrateFor(int baseBitrate, int fps) {
        if (fps <= 30 || baseBitrate <= 0) {
            return baseBitrate;
        }
        return (int) Math.min((long) baseBitrate * 3 / 2, Math.max(baseBitrate, MAX_BITRATE));
    }

    // Some old camera drivers report Camera2 ranges in fps x 1000, like the old API does.
    private static int fps(Integer value) {
        if (value == null) {
            return 0;
        }
        return value >= 1000 ? value / 1000 : value;
    }
}
