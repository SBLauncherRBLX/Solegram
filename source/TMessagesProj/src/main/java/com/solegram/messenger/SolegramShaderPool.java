/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.RuntimeShader;
import android.os.Build;
import android.util.SparseArray;

import androidx.annotation.RawRes;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ready AGSL shaders for the glass, the lenses and the wallpaper. Making a RuntimeShader reads its source from the
 * resources and compiles it; done on the UI thread for every glass element of a screen that opens (a profile has
 * many), that is a visible hitch. The sources are read once, and fresh shaders are compiled ahead on a background
 * thread: a screen takes a ready one and the pool is filled up again in the background. A fresh shader from the pool
 * is the same as a new one (no uniforms set).
 */
public final class SolegramShaderPool {

    private static final SparseArray<String> sources = new SparseArray<>();
    private static final SparseArray<ConcurrentLinkedQueue<Object>> pools = new SparseArray<>();
    private static final SparseArray<Boolean> filling = new SparseArray<>();
    private static final SparseArray<Integer> targets = new SparseArray<>();
    private static volatile boolean broken;

    private SolegramShaderPool() {
    }

    /** The shader's source, read once. */
    public static String source(@RawRes int res) {
        synchronized (sources) {
            String s = sources.get(res);
            if (s == null) {
                s = AndroidUtilities.readRes(res);
                if (s != null) {
                    sources.put(res, s);
                }
            }
            return s;
        }
    }

    /** A ready shader of this source (compiled now only when none is waiting); the pool fills up again behind it. */
    @RequiresApi(33)
    public static RuntimeShader take(@RawRes int res) {
        final ConcurrentLinkedQueue<Object> pool = pool(res);
        final Object ready = pool.poll();
        refill(res);
        if (ready instanceof RuntimeShader) {
            return (RuntimeShader) ready;
        }
        return new RuntimeShader(source(res));
    }

    private static ConcurrentLinkedQueue<Object> pool(int res) {
        synchronized (pools) {
            ConcurrentLinkedQueue<Object> q = pools.get(res);
            if (q == null) {
                q = new ConcurrentLinkedQueue<>();
                pools.put(res, q);
            }
            return q;
        }
    }

    private static void refill(int res) {
        if (Build.VERSION.SDK_INT < 33 || broken) {
            return;
        }
        final int target;
        synchronized (pools) {
            final Integer t = targets.get(res);
            target = t != null ? t : 2;
            if (Boolean.TRUE.equals(filling.get(res)) || pool(res).size() >= target) {
                return;
            }
            filling.put(res, true);
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final ConcurrentLinkedQueue<Object> pool = pool(res);
                final String src = source(res);
                while (src != null && pool.size() < target) {
                    pool.add(new RuntimeShader(src));
                }
            } catch (Throwable e) {
                // a GPU driver that refuses the shader: the screens find out themselves and fall back
                broken = true;
                FileLog.e(e);
            } finally {
                synchronized (pools) {
                    filling.put(res, false);
                }
            }
        });
    }

    private static void keep(int res, int count) {
        synchronized (pools) {
            targets.put(res, count);
        }
        refill(res);
    }

    /** At start, in the background: shaders for the first screens (glass elements, tab lens, wallpaper). */
    public static void warmUp() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        keep(R.raw.solegram_glass_shader, 10);
        keep(R.raw.liquid_glass_arrow_shader, 2);
        keep(R.raw.wallpaper_pos_intensity, 2);
        keep(R.raw.wallpaper_neg_intensity, 2);
        keep(R.raw.solegram_tab_lens_shader, 1);
        keep(R.raw.solegram_lens_shader, 3);
    }
}
