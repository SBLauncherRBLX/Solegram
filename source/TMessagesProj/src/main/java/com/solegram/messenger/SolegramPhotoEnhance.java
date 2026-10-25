/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;

import androidx.exifinterface.media.ExifInterface;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Processing of photos from the in-app camera, what a phone's own camera app does after the
 * shot: local tone mapping (shadows lifted, highlights rolled off, like a mild HDR), noise
 * reduction where the picture is dark and noisy, fine detail sharpening, a gentle vibrance that
 * leaves skin alone, and a white balance fix only when the colour cast is clear.
 *
 * Works on the size photos are sent at (2560 on the long side), in parallel stripes on the CPU,
 * and keeps the original file whenever anything goes wrong.
 */
public final class SolegramPhotoEnhance {

    /** Long side of the processed photo: Telegram's HD photo size. */
    private static final int MAX_SIDE = 2560;
    private static final int MAX_SIDE_LOW_END = 1920;
    private static final int MIN_SIDE = 480;
    private static final int JPEG_QUALITY = 95;

    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    // Tags worth keeping on the processed file; the orientation one is a must.
    private static final String[] EXIF_TAGS = {
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
    };

    private static ThreadPoolExecutor queue;
    private static ThreadPoolExecutor workers;

    private SolegramPhotoEnhance() {
    }

    public static boolean enabled() {
        return SolegramConfig.cameraEnhance;
    }

    /** Runs a job off the camera and UI threads, one photo at a time. */
    public static void execute(Runnable runnable) {
        try {
            queue().execute(runnable);
        } catch (Throwable e) {
            FileLog.e(e);
            runnable.run();
        }
    }

    private static synchronized ThreadPoolExecutor queue() {
        if (queue == null) {
            queue = newPool(1, "sgPhoto");
        }
        return queue;
    }

    private static synchronized ThreadPoolExecutor workers() {
        if (workers == null) {
            workers = newPool(THREADS, "sgPhotoWorker");
        }
        return workers;
    }

    private static ThreadPoolExecutor newPool(int threads, String name) {
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            final Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Processes the photo in the file and writes it back (JPEG, EXIF orientation kept, pixels
     * not rotated). Returns the processed bitmap, oriented like the file's pixels, for the caller
     * to cache or recycle; null when off or on any failure, the file is then left as it was.
     * Call off the UI thread.
     */
    public static Bitmap enhanceFile(File file) {
        if (!enabled() || file == null || !file.exists()) {
            return null;
        }
        final long start = System.currentTimeMillis();
        Bitmap bitmap = null;
        File tmp = null;
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            final int srcW = bounds.outWidth, srcH = bounds.outHeight;
            if (srcW <= 0 || srcH <= 0 || Math.max(srcW, srcH) < MIN_SIDE) {
                return null;
            }
            int maxSide = SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW ? MAX_SIDE_LOW_END : MAX_SIDE;
            // smaller when memory is short, skipped when even that does not fit
            while (!fits(srcW, srcH, maxSide)) {
                if (maxSide <= 1280) {
                    FileLog.d("SolegramPhotoEnhance: not enough memory, photo left as it is");
                    return null;
                }
                maxSide = maxSide * 3 / 4;
            }
            bitmap = decode(file, srcW, srcH, maxSide);
            if (bitmap == null) {
                return null;
            }
            final int w = bitmap.getWidth(), h = bitmap.getHeight();
            final int[] px = new int[w * h];
            bitmap.getPixels(px, 0, w, 0, 0, w, h);
            final float downscale = Math.max(srcW, srcH) / (float) Math.max(w, h);
            process(px, w, h, downscale);
            bitmap.setPixels(px, 0, w, 0, 0, w, h);

            tmp = new File(file.getAbsolutePath() + ".sg.tmp"); // same folder, so the rename replaces in place
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw new IllegalStateException("compress failed");
                }
                out.flush();
                out.getFD().sync();
            }
            copyExif(file, tmp);
            if (!tmp.renameTo(file)) {
                throw new IllegalStateException("rename failed");
            }
            tmp = null;
            FileLog.d("SolegramPhotoEnhance: " + srcW + "x" + srcH + " -> " + w + "x" + h + " in " + (System.currentTimeMillis() - start) + " ms");
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            if (bitmap != null) {
                bitmap.recycle();
            }
            return null;
        } finally {
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Cuts the middle of the photo in the file to a shape (long side over short side: 1 for the 1:1
     * frame, the screen's shape for the full screen frame) and writes it back, EXIF kept. The crop is
     * the middle of the sensor's picture, so the orientation in EXIF doesn't matter. {@code current}
     * is the photo as the file holds it now, when the caller has it decoded (processed or turned):
     * then that is cut, else only that region of the file is decoded (halved while it stays above
     * {@link #MAX_SIDE}; the camera's photos are 4096 at most). Returns the cut bitmap (the given one is recycled), or {@code current} when nothing was
     * cut (the shape already fits, or a failure: the file is then left as it was). Off the UI thread.
     */
    public static Bitmap cropToRatio(File file, Bitmap current, float ratio) {
        if (file == null || !file.exists() || ratio < 1f) {
            return current;
        }
        Bitmap cut = null;
        File tmp = null;
        try {
            if (current != null && !current.isRecycled()) {
                final int w = current.getWidth(), h = current.getHeight();
                final Rect crop = cropRect(w, h, ratio);
                if (crop == null) {
                    return current;
                }
                cut = Bitmap.createBitmap(current, crop.left, crop.top, crop.width(), crop.height());
            } else {
                final BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                final int w = bounds.outWidth, h = bounds.outHeight;
                final Rect crop = w > 0 && h > 0 ? cropRect(w, h, ratio) : null;
                if (crop == null) {
                    return current;
                }
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(crop.width(), crop.height(), MAX_SIDE);
                final android.graphics.BitmapRegionDecoder decoder = android.graphics.BitmapRegionDecoder.newInstance(file.getAbsolutePath(), false);
                try {
                    cut = decoder.decodeRegion(crop, options);
                } finally {
                    decoder.recycle();
                }
                if (cut == null) {
                    return current;
                }
            }
            tmp = new File(file.getAbsolutePath() + ".crop.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!cut.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw new IllegalStateException("compress failed");
                }
                out.flush();
                out.getFD().sync();
            }
            copyExif(file, tmp);
            if (!tmp.renameTo(file)) {
                throw new IllegalStateException("rename failed");
            }
            tmp = null;
            if (current != null && current != cut) {
                current.recycle();
            }
            return cut;
        } catch (Throwable e) {
            FileLog.e(e);
            if (cut != null && cut != current) {
                cut.recycle();
            }
            return current;
        } finally {
            if (tmp != null) {
                try {
                    tmp.delete();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // The middle part of a w x h picture with the given shape, null when it already has it (within 1%).
    private static Rect cropRect(int w, int h, float ratio) {
        final int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        final float current = (float) longSide / shortSide;
        if (Math.abs(current - ratio) < ratio * 0.01f) {
            return null;
        }
        int newLong = longSide, newShort = shortSide;
        if (current > ratio) {
            newLong = Math.round(shortSide * ratio); // too long: the ends go (1:1 from 4:3)
        } else {
            newShort = Math.round(longSide / ratio); // too wide: the sides go (20:9 from 16:9)
        }
        final int cw = w >= h ? newLong : newShort, ch = w >= h ? newShort : newLong;
        if (cw <= 0 || ch <= 0) {
            return null;
        }
        final int left = (w - cw) / 2, top = (h - ch) / 2;
        return new Rect(left, top, left + cw, top + ch);
    }

    // Java heap needed: the pixel array and two float planes at the processed size, and before
    // Android 8 the bitmaps too (they live outside the Java heap since then).
    private static boolean fits(int srcW, int srcH, int maxSide) {
        final float scale = Math.min(1f, maxSide / (float) Math.max(srcW, srcH));
        final long outPixels = (long) (srcW * scale) * (long) (srcH * scale);
        long need = outPixels * 4 * 3 + outPixels;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            need += outPixels * 4 + (long) srcW * srcH * 4 / (sampleSize(srcW, srcH, maxSide) * sampleSize(srcW, srcH, maxSide));
        }
        final Runtime runtime = Runtime.getRuntime();
        final long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return need + 24L * 1024 * 1024 < free;
    }

    private static int sampleSize(int srcW, int srcH, int maxSide) {
        int sample = 1;
        while (Math.max(srcW, srcH) / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        return sample;
    }

    // Decodes at a power of two at or above the target, then a filtered downscale to it.
    private static Bitmap decode(File file, int srcW, int srcH, int maxSide) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(srcW, srcH, maxSide);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        final Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) {
            return null;
        }
        final int dw = decoded.getWidth(), dh = decoded.getHeight();
        final float scale = Math.min(1f, maxSide / (float) Math.max(dw, dh));
        if (scale >= 1f && decoded.isMutable() && decoded.getConfig() == Bitmap.Config.ARGB_8888) {
            return decoded;
        }
        final int w = Math.max(1, Math.round(dw * scale)), h = Math.max(1, Math.round(dh * scale));
        final Bitmap result;
        try {
            result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            new Canvas(result).drawBitmap(decoded, null, new Rect(0, 0, w, h), paint);
        } finally {
            decoded.recycle();
        }
        return result;
    }

    private static void copyExif(File from, File to) throws Exception {
        final ExifInterface source = new ExifInterface(from.getAbsolutePath());
        final ExifInterface target = new ExifInterface(to.getAbsolutePath());
        boolean changed = false;
        for (String tag : EXIF_TAGS) {
            final String value = source.getAttribute(tag);
            if (value != null) {
                target.setAttribute(tag, value);
                changed = true;
            }
        }
        if (changed) {
            target.saveAttributes();
        }
    }

    // region Processing

    private interface Rows {
        void run(int from, int to);
    }

    // Splits rows into stripes over the worker threads; any failure comes back as an exception.
    private static void parallel(int rows, Rows task) throws Exception {
        final int count = Math.min(THREADS, Math.max(1, rows / 64));
        if (count <= 1) {
            task.run(0, rows);
            return;
        }
        final Future<?>[] futures = new Future<?>[count];
        for (int i = 0; i < count; i++) {
            final int from = (int) ((long) rows * i / count);
            final int to = (int) ((long) rows * (i + 1) / count);
            futures[i] = workers().submit(() -> task.run(from, to));
        }
        for (Future<?> future : futures) {
            future.get();
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        final float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float luma(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) * (1f / 255f);
    }

    /** What the statistics of the photo ask for. */
    private static final class Plan {
        float gainR = 1f, gainG = 1f, gainB = 1f;
        boolean whiteBalance;
        float shadows, highlights;
        float blackPoint = 0f, whitePoint = 1f;
        float noise;        // estimated noise, 0..1 luma units
        float lumaNr, chromaNr;
        float clarity, sharpen, sharpenCore, vibrance;
    }

    private static void process(int[] px, int w, int h, float downscale) throws Exception {
        final Plan plan = analyze(px, w, h, downscale);
        final int n = w * h;

        // 1. white balance into the pixels, luma into a plane
        final float[] lum = new float[n];
        parallel(h, (from, to) -> {
            final float gr = plan.gainR, gg = plan.gainG, gb = plan.gainB;
            final boolean wb = plan.whiteBalance;
            for (int i = from * w, end = to * w; i < end; i++) {
                int c = px[i];
                if (wb) {
                    final int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * gr));
                    final int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * gg));
                    final int b = Math.min(255, Math.round((c & 0xFF) * gb));
                    c = 0xFF000000 | (r << 16) | (g << 8) | b;
                    px[i] = c;
                }
                lum[i] = luma(c);
            }
        });

        // 2. luma noise reduction where it is dark and noisy (the tone mapping lifts that noise)
        if (plan.lumaNr > 0.02f) {
            denoiseLuma(lum, w, h, plan);
        }

        // 3. chroma noise at half size, used with an edge test in the final pass
        final ChromaPlanes chroma = plan.chromaNr > 0.02f ? smoothChroma(px, w, h) : null;

        // 4. base layer for local tone mapping: an edge-aware blur (fast guided filter)
        final BaseLayer base = baseLayer(lum, w, h);

        // 5. everything put together per pixel
        finalPass(px, lum, w, h, plan, base, chroma);
    }

    private static Plan analyze(int[] px, int w, int h, float downscale) {
        final Plan plan = new Plan();
        final int step = Math.max(1, (int) Math.sqrt((double) w * h / 300_000.0));
        final int[] hist = new int[256];
        final int[] lapHist = new int[1024];
        long samples = 0, lapSamples = 0;
        double grayR = 0, grayG = 0, grayB = 0, satSum = 0;
        long grayCount = 0;
        for (int y = 1; y < h - 1; y += step) {
            final int row = y * w;
            for (int x = 1; x < w - 1; x += step) {
                final int c = px[row + x];
                final int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                final int y8 = (77 * r + 150 * g + 29 * b) >> 8;
                hist[y8]++;
                samples++;
                final int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max > 0) {
                    satSum += (max - min) / (float) max;
                }
                // pixels that are nearly grey in the scene show the colour cast
                if (y8 >= 50 && y8 <= 235 && max - min < max * 0.3f) {
                    grayR += r;
                    grayG += g;
                    grayB += b;
                    grayCount++;
                }
                if (y8 >= 8 && y8 <= 247) {
                    final int lap = Math.abs(4 * y8 - lum8(px[row + x - 1]) - lum8(px[row + x + 1]) - lum8(px[row + x - w]) - lum8(px[row + x + w]));
                    lapHist[Math.min(1023, lap)]++;
                    lapSamples++;
                }
            }
        }
        if (samples == 0) {
            return plan;
        }

        final float mean = mean(hist, samples);
        final float dark = fraction(hist, 0, 46, samples);
        final float brightUnclipped = fraction(hist, 204, 250, samples);
        final float p005 = percentile(hist, samples, 0.005f);
        final float p995 = percentile(hist, samples, 0.995f);
        final float meanSat = (float) (satSum / samples);

        // noise: the 25th percentile of the Laplacian, robust to texture; for Gaussian noise of
        // sigma s the 4-neighbour Laplacian has sigma 4.47 s and its 25th |value| percentile 0.319 of that
        final float lap25 = lapSamples > 0 ? percentile(lapHist, lapSamples, 0.25f) * (lapHist.length - 1) : 0f;
        plan.noise = lap25 / (0.319f * 4.47f) / 255f;
        final float noise255 = plan.noise * 255f;

        // white balance: only a clear cast, and only part of it; warm light is often meant to be warm
        if (grayCount > samples / 50 && mean > 0.12f) {
            final float aR = (float) (grayR / grayCount), aG = (float) (grayG / grayCount), aB = (float) (grayB / grayCount);
            if (aR > 1f && aG > 1f && aB > 1f) {
                final float lr = (float) Math.log(aG / aR), lb = (float) Math.log(aG / aB);
                final float deviation = Math.max(Math.abs(lr), Math.abs(lb));
                if (deviation > 0.06f) {
                    float strength = Math.min(1f, (deviation - 0.06f) / 0.15f) * 0.6f;
                    if (lb > 0 && lb > lr) {
                        strength *= 0.5f; // blue is short: a warm cast
                    }
                    float gr = clamp((float) Math.exp(lr * strength), 0.88f, 1.14f);
                    float gb = clamp((float) Math.exp(lb * strength), 0.88f, 1.14f);
                    final float norm = 1f / (0.299f * gr + 0.587f + 0.114f * gb);
                    plan.gainR = gr * norm;
                    plan.gainG = norm;
                    plan.gainB = gb * norm;
                    plan.whiteBalance = Math.abs(plan.gainR - 1f) > 0.005f || Math.abs(plan.gainB - 1f) > 0.005f || Math.abs(norm - 1f) > 0.005f;
                }
            }
        }

        // tone: shadows lifted by how much of the picture is dark, highlights rolled off by how
        // much is bright but not clipped
        // (only a picture with a lot of deep shadow, and gently: a lift of the whole frame looked washed out)
        plan.shadows = clamp((dark - 0.10f) / 0.40f, 0f, 1f);
        if (mean < 0.12f) {
            plan.shadows *= 0.75f; // night: brighter, not day
        }
        plan.highlights = clamp((brightUnclipped - 0.04f) / 0.25f, 0f, 1f);
        // levels: a hazy black lowered, a dull white raised, both gently
        if (p005 > 0.03f) {
            plan.blackPoint = Math.min(p005 * 0.6f, 0.04f);
        }
        // a white point stretch brightened the whole frame: only a really dull picture, and a little
        if (p995 < 0.75f && mean > 0.08f) {
            plan.whitePoint = Math.max(p995 / 0.80f, 1f / 1.06f);
        }

        // noise reduction: where it is dark, how noisy decides how much
        final float darkness = Math.max(clamp((0.5f - mean) / 0.3f, 0f, 1f), plan.shadows * 0.7f);
        plan.lumaNr = clamp((noise255 - 1.2f) / 2.5f, 0f, 1f) * darkness;
        plan.chromaNr = clamp((noise255 - 1.0f) / 2.0f, 0f, 1f) * Math.max(darkness, 0.3f);

        plan.clarity = 0.12f;
        // a photo shrunk from a bigger one is already crisp: less sharpening
        plan.sharpen = downscale > 1.3f ? 0.34f : 0.42f;
        plan.sharpenCore = Math.max(0.004f, 1.5f * plan.noise);
        plan.vibrance = 0.14f * clamp(1.4f - meanSat * 2.5f, 0.3f, 1f);
        return plan;
    }

    private static int lum8(int c) {
        return (77 * ((c >> 16) & 0xFF) + 150 * ((c >> 8) & 0xFF) + 29 * (c & 0xFF)) >> 8;
    }

    private static float mean(int[] hist, long total) {
        double sum = 0;
        for (int i = 0; i < hist.length; i++) {
            sum += (double) i * hist[i];
        }
        return (float) (sum / total / (hist.length - 1));
    }

    private static float fraction(int[] hist, int from, int to, long total) {
        long count = 0;
        for (int i = from; i <= to; i++) {
            count += hist[i];
        }
        return count / (float) total;
    }

    // Value (0..1 of the histogram's range) below which the given part of the samples lies.
    private static float percentile(int[] hist, long total, float part) {
        final long target = (long) (total * part);
        long count = 0;
        for (int i = 0; i < hist.length; i++) {
            count += hist[i];
            if (count > target) {
                return i / (float) (hist.length - 1);
            }
        }
        return 1f;
    }

    // Separable 5x5 bilateral on luma, blended in more where the picture is darker.
    private static void denoiseLuma(float[] lum, int w, int h, Plan plan) throws Exception {
        final float sigma = Math.max(0.01f, 2.5f * plan.noise);
        final float[] range = new float[1024]; // exp(-t^2/2) for t = index / 64 sigmas, 0 past 4 sigmas
        for (int i = 0; i < 256; i++) {
            final float t = i / 64f;
            range[i] = (float) Math.exp(-0.5 * t * t);
        }
        final float toIndex = 64f / sigma;
        final float[] spatial = {0.41f, 0.8f, 1f, 0.8f, 0.41f};
        final float[] tmp = new float[w * h];
        parallel(h, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int row = y * w;
                for (int x = 0; x < w; x++) {
                    final float center = lum[row + x];
                    float sum = 0f, weight = 0f;
                    for (int k = -2; k <= 2; k++) {
                        final float v = lum[row + Math.max(0, Math.min(w - 1, x + k))];
                        final float wt = spatial[k + 2] * range[Math.min(1023, (int) (Math.abs(v - center) * toIndex))];
                        sum += v * wt;
                        weight += wt;
                    }
                    tmp[row + x] = sum / weight;
                }
            }
        });
        final float strength = plan.lumaNr;
        parallel(h, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int row = y * w;
                final int r0 = Math.max(0, y - 2) * w, r1 = Math.max(0, y - 1) * w, r3 = Math.min(h - 1, y + 1) * w, r4 = Math.min(h - 1, y + 2) * w;
                for (int x = 0; x < w; x++) {
                    final float center = tmp[row + x];
                    final float v0 = tmp[r0 + x], v1 = tmp[r1 + x], v3 = tmp[r3 + x], v4 = tmp[r4 + x];
                    final float w0 = spatial[0] * range[Math.min(1023, (int) (Math.abs(v0 - center) * toIndex))];
                    final float w1 = spatial[1] * range[Math.min(1023, (int) (Math.abs(v1 - center) * toIndex))];
                    final float w3 = spatial[3] * range[Math.min(1023, (int) (Math.abs(v3 - center) * toIndex))];
                    final float w4 = spatial[4] * range[Math.min(1023, (int) (Math.abs(v4 - center) * toIndex))];
                    final float sum = center + v0 * w0 + v1 * w1 + v3 * w3 + v4 * w4;
                    final float weight = 1f + w0 + w1 + w3 + w4;
                    final float original = lum[row + x];
                    final float amount = strength * (1f - 0.6f * smoothstep(0.25f, 0.65f, original));
                    lum[row + x] = original + (sum / weight - original) * amount;
                }
            }
        });
    }

    private static final class ChromaPlanes {
        int w, h;
        float[] cr, cb;
    }

    // Colour difference planes (R-Y, B-Y) at half size, box blurred: blotchy colour noise is
    // coarse, so this removes it while the edge test in the final pass keeps real colour edges.
    private static ChromaPlanes smoothChroma(int[] px, int w, int h) throws Exception {
        final ChromaPlanes planes = new ChromaPlanes();
        final int cw = (w + 1) / 2, ch = (h + 1) / 2;
        planes.w = cw;
        planes.h = ch;
        planes.cr = new float[cw * ch];
        planes.cb = new float[cw * ch];
        parallel(ch, (from, to) -> {
            for (int y = from; y < to; y++) {
                for (int x = 0; x < cw; x++) {
                    float cr = 0f, cb = 0f;
                    int count = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        final int yy = Math.min(h - 1, y * 2 + dy);
                        for (int dx = 0; dx < 2; dx++) {
                            final int c = px[yy * w + Math.min(w - 1, x * 2 + dx)];
                            final float l = luma(c);
                            cr += ((c >> 16) & 0xFF) * (1f / 255f) - l;
                            cb += (c & 0xFF) * (1f / 255f) - l;
                            count++;
                        }
                    }
                    planes.cr[y * cw + x] = cr / count;
                    planes.cb[y * cw + x] = cb / count;
                }
            }
        });
        final float[] tmp = new float[cw * ch];
        boxBlur(planes.cr, tmp, cw, ch, 2);
        boxBlur(planes.cb, tmp, cw, ch, 2);
        return planes;
    }

    private static final class BaseLayer {
        int scale, w, h;
        float[] a, b;
    }

    // Fast guided filter (He & Sun): the luma guides itself at low resolution, the linear
    // coefficients are blurred and brought back up. Flat areas get a smooth base, strong edges
    // follow the pixel, so lifting shadows leaves no halos along edges.
    private static BaseLayer baseLayer(float[] lum, int w, int h) throws Exception {
        final BaseLayer layer = new BaseLayer();
        final int scale = Math.max(1, (int) Math.ceil(Math.max(w, h) / 320f));
        final int lw = (w + scale - 1) / scale, lh = (h + scale - 1) / scale;
        layer.scale = scale;
        layer.w = lw;
        layer.h = lh;
        final float[] small = new float[lw * lh];
        parallel(lh, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int y0 = y * scale, y1 = Math.min(h, y0 + scale);
                for (int x = 0; x < lw; x++) {
                    final int x0 = x * scale, x1 = Math.min(w, x0 + scale);
                    float sum = 0f;
                    for (int yy = y0; yy < y1; yy++) {
                        final int row = yy * w;
                        for (int xx = x0; xx < x1; xx++) {
                            sum += lum[row + xx];
                        }
                    }
                    small[y * lw + x] = sum / ((y1 - y0) * (x1 - x0));
                }
            }
        });
        final int count = lw * lh;
        final int radius = Math.max(2, Math.round(Math.max(lw, lh) * 0.03f));
        final float eps = 0.012f;
        final float[] tmp = new float[count];
        final float[] meanI = small.clone();
        final float[] meanII = new float[count];
        for (int i = 0; i < count; i++) {
            meanII[i] = small[i] * small[i];
        }
        boxBlur(meanI, tmp, lw, lh, radius);
        boxBlur(meanII, tmp, lw, lh, radius);
        final float[] a = new float[count];
        final float[] b = new float[count];
        for (int i = 0; i < count; i++) {
            final float var = Math.max(0f, meanII[i] - meanI[i] * meanI[i]);
            a[i] = var / (var + eps);
            b[i] = meanI[i] - a[i] * meanI[i];
        }
        boxBlur(a, tmp, lw, lh, radius);
        boxBlur(b, tmp, lw, lh, radius);
        layer.a = a;
        layer.b = b;
        return layer;
    }

    // In-place box blur with edge clamping, running sums: O(n) for any radius. The vertical
    // pass keeps a sum per column and walks rows, so memory is read in order.
    private static void boxBlur(float[] data, float[] tmp, int w, int h, int r) {
        final float norm = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            float sum = 0f;
            for (int k = -r; k <= r; k++) {
                sum += data[row + Math.max(0, Math.min(w - 1, k))];
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = sum * norm;
                sum += data[row + Math.min(w - 1, x + r + 1)] - data[row + Math.max(0, x - r)];
            }
        }
        final float[] sums = new float[w];
        for (int k = -r; k <= r; k++) {
            final int row = Math.max(0, Math.min(h - 1, k)) * w;
            for (int x = 0; x < w; x++) {
                sums[x] += tmp[row + x];
            }
        }
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            final int add = Math.min(h - 1, y + r + 1) * w, remove = Math.max(0, y - r) * w;
            for (int x = 0; x < w; x++) {
                data[row + x] = sums[x] * norm;
                sums[x] += tmp[add + x] - tmp[remove + x];
            }
        }
    }

    private static final int LUT_SIZE = 1024;
    // strength of the mid-tone S-curve (stays monotonic below 0.5)
    private static final float CONTRAST = 0.07f;

    // Tone curve on the base layer, as gain = f(base) / base. Shadows: B + k B (1-B)^4 lifts only the
    // deep darks (up to ~1.3x) and leaves black black and the mid-tones as they were; highlights:
    // - k (1-B) B^3 rolls off the brights and leaves white white. Both stay monotonic.
    private static float[] toneGainLut(Plan plan) {
        final float[] lut = new float[LUT_SIZE + 1];
        final float ks = 0.32f * plan.shadows, kh = 0.5f * plan.highlights;
        for (int i = 0; i <= LUT_SIZE; i++) {
            final float b = i / (float) LUT_SIZE;
            final float ib = 1f - b;
            if (i == 0) {
                lut[i] = 1f + ks;
            } else {
                final float f = b + ks * b * ib * ib * ib * ib - kh * ib * b * b * b;
                lut[i] = f / b;
            }
        }
        return lut;
    }

    private static float[] levelsLut(Plan plan) {
        final float[] lut = new float[LUT_SIZE + 1];
        final float bp = plan.blackPoint, wp = plan.whitePoint;
        for (int i = 0; i <= LUT_SIZE; i++) {
            final float v = i / (float) LUT_SIZE;
            lut[i] = clamp((v - bp) / (wp - bp), 0f, 1f);
        }
        return lut;
    }

    private static float lookup(float[] lut, float v) {
        final float p = clamp(v, 0f, 1f) * LUT_SIZE;
        final int i = Math.min(LUT_SIZE - 1, (int) p);
        final float t = p - i;
        return lut[i] + (lut[i + 1] - lut[i]) * t;
    }

    private static void finalPass(int[] px, float[] lum, int w, int h, Plan plan, BaseLayer base, ChromaPlanes chroma) throws Exception {
        final float[] toneLut = toneGainLut(plan);
        final float[] levels = levelsLut(plan);
        final boolean useLevels = plan.blackPoint > 0f || plan.whitePoint < 1f;

        // sampling positions of the low resolution planes, per column
        final int[] bx0 = new int[w];
        final float[] bfx = new float[w];
        for (int x = 0; x < w; x++) {
            final float fx = clamp((x + 0.5f) / base.scale - 0.5f, 0f, base.w - 1);
            bx0[x] = Math.min(base.w - 2 < 0 ? 0 : base.w - 2, (int) fx);
            bfx[x] = base.w < 2 ? 0f : fx - bx0[x];
        }
        final int[] cx0 = chroma == null ? null : new int[w];
        final float[] cfx = chroma == null ? null : new float[w];
        if (chroma != null) {
            for (int x = 0; x < w; x++) {
                final float fx = clamp((x + 0.5f) / 2f - 0.5f, 0f, chroma.w - 1);
                cx0[x] = Math.min(chroma.w - 2 < 0 ? 0 : chroma.w - 2, (int) fx);
                cfx[x] = chroma.w < 2 ? 0f : fx - cx0[x];
            }
        }

        final float clarity = plan.clarity, sharpen = plan.sharpen, core = plan.sharpenCore, vibrance = plan.vibrance;
        final float chromaNr = plan.chromaNr;
        final float chromaEdge = Math.max(0.02f, 4f * plan.noise);

        parallel(h, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int row = y * w;
                final int up = Math.max(0, y - 1) * w, down = Math.min(h - 1, y + 1) * w;

                final float fy = clamp((y + 0.5f) / base.scale - 0.5f, 0f, base.h - 1);
                final int by0 = Math.min(base.h - 2 < 0 ? 0 : base.h - 2, (int) fy);
                final int by1 = Math.min(base.h - 1, by0 + 1);
                final float bfy = base.h < 2 ? 0f : fy - by0;

                int cy0 = 0, cy1 = 0;
                float cfy = 0f;
                if (chroma != null) {
                    final float f = clamp((y + 0.5f) / 2f - 0.5f, 0f, chroma.h - 1);
                    cy0 = Math.min(chroma.h - 2 < 0 ? 0 : chroma.h - 2, (int) f);
                    cy1 = Math.min(chroma.h - 1, cy0 + 1);
                    cfy = chroma.h < 2 ? 0f : f - cy0;
                }

                for (int x = 0; x < w; x++) {
                    final int i = row + x;
                    final int c = px[i];
                    float r = ((c >> 16) & 0xFF) * (1f / 255f);
                    float g = ((c >> 8) & 0xFF) * (1f / 255f);
                    float b = (c & 0xFF) * (1f / 255f);
                    final float yin = 0.299f * r + 0.587f * g + 0.114f * b;
                    final float yd = lum[i];

                    // chroma noise: towards the smoothed colour unless this is a real colour edge
                    if (chroma != null) {
                        final int x0 = cx0[x], x1 = Math.min(chroma.w - 1, x0 + 1);
                        final float fx = cfx[x];
                        final float crs = bilinear(chroma.cr, chroma.w, x0, x1, cy0, cy1, fx, cfy);
                        final float cbs = bilinear(chroma.cb, chroma.w, x0, x1, cy0, cy1, fx, cfy);
                        final float cr = r - yin, cb = b - yin;
                        final float diff = Math.max(Math.abs(cr - crs), Math.abs(cb - cbs));
                        final float amount = chromaNr * (1f - smoothstep(chromaEdge, chromaEdge * 2.5f, diff));
                        if (amount > 0f) {
                            r = yin + cr + (crs - cr) * amount;
                            b = yin + cb + (cbs - cb) * amount;
                            g = (yin - 0.299f * r - 0.114f * b) * (1f / 0.587f);
                        }
                    }

                    // local tone mapping with a little extra local contrast
                    final int x0 = bx0[x], x1 = Math.min(base.w - 1, x0 + 1);
                    final float a = bilinear(base.a, base.w, x0, x1, by0, by1, bfx[x], bfy);
                    final float bb = bilinear(base.b, base.w, x0, x1, by0, by1, bfx[x], bfy);
                    final float baseL = clamp(a * yd + bb, 0.002f, 1f);
                    final float toneGain = lookup(toneLut, baseL);
                    final float detail = yd / baseL;
                    final float localContrast = clarity * smoothstep(0.04f, 0.2f, baseL);
                    float yn = baseL * toneGain * clamp(1f + (detail - 1f) * (1f + localContrast), 0f, 4f);

                    // fine detail: unsharp mask on luma with a noise floor, limited so it cannot halo
                    final int xl = x > 0 ? x - 1 : x, xr = x < w - 1 ? x + 1 : x;
                    final float blur = (4f * yd
                        + 2f * (lum[row + xl] + lum[row + xr] + lum[up + x] + lum[down + x])
                        + lum[up + xl] + lum[up + xr] + lum[down + xl] + lum[down + xr]) * (1f / 16f);
                    float high = yd - blur;
                    if (high > core) {
                        high -= core;
                    } else if (high < -core) {
                        high += core;
                    } else {
                        high = 0f;
                    }
                    if (high != 0f) {
                        final float amount = sharpen * smoothstep(0.03f, 0.15f, yd) * Math.min(toneGain, 2f);
                        yn += clamp(high * amount, -0.05f, 0.05f);
                    }

                    if (useLevels) {
                        yn = lookup(levels, yn);
                    }
                    yn = clamp(yn, 0f, 1f);
                    // a slight S-curve: firmer mid-tone contrast, the depth of a good camera's picture
                    yn += CONTRAST * (yn - 0.5f) * 4f * yn * (1f - yn);

                    // the new luma onto the colour: scaled, the rest (deep black) added as grey
                    float k = yin > 1e-4f ? yn / yin : 6f;
                    if (k > 6f) {
                        k = 6f;
                    }
                    final float add = yn - yin * k;
                    r = r * k + add;
                    g = g * k + add;
                    b = b * k + add;

                    // vibrance: muted colours more than vivid ones, skin tones barely
                    float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                    if (vibrance > 0f && max > 1e-3f) {
                        final float sat = clamp((max - min) / max, 0f, 1f);
                        float amount = vibrance * (1f - sat) * (1f - sat);
                        if (r >= g && g >= b && r > b) {
                            final float hue = 60f * (g - b) / (r - b);
                            if (hue > 8f && hue < 50f) {
                                amount *= 0.3f;
                            }
                        }
                        r = yn + (r - yn) * (1f + amount);
                        g = yn + (g - yn) * (1f + amount);
                        b = yn + (b - yn) * (1f + amount);
                        max = Math.max(r, Math.max(g, b));
                        min = Math.min(r, Math.min(g, b));
                    }

                    // out of range: towards grey at the same luma rather than a clipped hue
                    if (max > 1f || min < 0f) {
                        float t = 1f;
                        if (max > 1f) {
                            t = Math.min(t, (1f - yn) / Math.max(1e-4f, max - yn));
                        }
                        if (min < 0f) {
                            t = Math.min(t, yn / Math.max(1e-4f, yn - min));
                        }
                        t = clamp(t, 0f, 1f);
                        r = yn + (r - yn) * t;
                        g = yn + (g - yn) * t;
                        b = yn + (b - yn) * t;
                    }

                    px[i] = 0xFF000000 | (to8(r) << 16) | (to8(g) << 8) | to8(b);
                }
            }
        });
    }

    private static float bilinear(float[] plane, int pw, int x0, int x1, int y0, int y1, float fx, float fy) {
        final float top = plane[y0 * pw + x0] + (plane[y0 * pw + x1] - plane[y0 * pw + x0]) * fx;
        final float bottom = plane[y1 * pw + x0] + (plane[y1 * pw + x1] - plane[y1 * pw + x0]) * fx;
        return top + (bottom - top) * fy;
    }

    private static int to8(float v) {
        final int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    // endregion
}
