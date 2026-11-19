/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Whisper (whisper.cpp, in libsgwhisper) for transcribing voice messages in any language, on the phone: the speech
 * model is downloaded once on the person's request (about 57 MB) and checked against its known SHA-256 before use;
 * the sound never leaves the phone. Whisper finds the language itself.
 */
public final class SolegramWhisper {

    private SolegramWhisper() {
    }

    /** The multilingual "base" model (5-bit), pinned to one revision of the whisper.cpp model repository. */
    private static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base-q5_1.bin";
    private static final String MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898";
    public static final long MODEL_SIZE = 59707625L;

    private static native boolean nAvailable();
    private static native long nInit(String path);
    private static native void nFree(long handle);
    private static native void nAbort();
    private static native String nTranscribe(long handle, short[] pcm, String language, int threads);

    private static int libraryState; // 0 unknown, 1 loaded, -1 missing
    private static long context;
    private static final Object lock = new Object();
    private static final Runnable freeLater = SolegramWhisper::freeContext;
    private static volatile boolean downloading;
    private static volatile boolean cancelDownload;

    /** Whether this build has Whisper at all (the library is in the APK for this phone's CPU). */
    public static synchronized boolean libraryAvailable() {
        if (libraryState == 0) {
            try {
                System.loadLibrary("sgwhisper");
                libraryState = nAvailable() ? 1 : -1;
            } catch (Throwable e) {
                libraryState = -1;
            }
        }
        return libraryState == 1;
    }

    private static File modelFile() {
        return new File(new File(ApplicationLoader.getFilesDirFixed(), "whisper"), "ggml-base-q5_1.bin");
    }

    /** Whether the model is on the phone (and was checked when it came). */
    public static boolean modelReady() {
        final File f = modelFile();
        return f.exists() && f.length() == MODEL_SIZE && SolegramConfig.prefs().getBoolean("whisperModelOk", false);
    }

    public static boolean ready() {
        return libraryAvailable() && modelReady();
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static void cancelDownload() {
        cancelDownload = true;
    }

    /** Removes the model (it can be downloaded again). */
    public static void deleteModel() {
        synchronized (lock) {
            freeContext();
            SolegramConfig.prefs().edit().remove("whisperModelOk").apply();
            //noinspection ResultOfMethodCallIgnored
            modelFile().delete();
        }
    }

    /**
     * Downloads the model on its own thread. {@code progress} gets 0..100 and {@code done} true when the model is
     * ready, both on the UI thread.
     */
    public static void download(Utilities.Callback<Integer> progress, Utilities.Callback<Boolean> done) {
        if (downloading) {
            return;
        }
        downloading = true;
        cancelDownload = false;
        new Thread(() -> {
            boolean ok = false;
            final File target = modelFile();
            final File part = new File(target.getParentFile(), target.getName() + ".part");
            HttpURLConnection connection = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                target.getParentFile().mkdirs();
                String url = MODEL_URL;
                // the model host answers with a redirect to its CDN (https only, a few hops at most)
                for (int hop = 0; hop < 5; hop++) {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(20_000);
                    connection.setReadTimeout(30_000);
                    connection.setRequestProperty("User-Agent", "Solegram");
                    final int code = connection.getResponseCode();
                    if (code >= 300 && code < 400) {
                        final String next = connection.getHeaderField("Location");
                        connection.disconnect();
                        connection = null;
                        if (next == null) {
                            break;
                        }
                        final URL resolved = new URL(new URL(url), next);
                        if (!"https".equals(resolved.getProtocol().toLowerCase(Locale.ROOT))) {
                            break;
                        }
                        url = resolved.toString();
                        continue;
                    }
                    break;
                }
                if (connection != null && connection.getResponseCode() == 200) {
                    final MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    long read = 0;
                    int lastPercent = -1;
                    try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                        final byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            if (cancelDownload) {
                                throw new InterruptedException("cancelled");
                            }
                            read += n;
                            if (read > MODEL_SIZE) {
                                throw new java.io.IOException("too large");
                            }
                            sha.update(buffer, 0, n);
                            out.write(buffer, 0, n);
                            final int percent = (int) (read * 100 / MODEL_SIZE);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                AndroidUtilities.runOnUIThread(() -> progress.run(percent));
                            }
                        }
                    }
                    if (read == MODEL_SIZE && MODEL_SHA256.equals(hex(sha.digest())) && part.renameTo(target)) {
                        SolegramConfig.prefs().edit().putBoolean("whisperModelOk", true).apply();
                        ok = true;
                    }
                }
            } catch (Throwable e) {
                if (!cancelDownload) {
                    FileLog.e(e);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (!ok) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                downloading = false;
                final boolean result = ok;
                AndroidUtilities.runOnUIThread(() -> done.run(result));
            }
        }, "sgWhisperModel").start();
    }

    private static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /**
     * The words in 16 kHz mono 16-bit PCM, any language (found by Whisper), or null. Slow: call off the UI thread.
     */
    @Nullable
    public static String transcribe(byte[] pcm16k) {
        if (!ready() || pcm16k == null || pcm16k.length < 3200) {
            return null;
        }
        final short[] samples = new short[pcm16k.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((pcm16k[i * 2] & 0xFF) | (pcm16k[i * 2 + 1] << 8));
        }
        synchronized (lock) {
            AndroidUtilities.cancelRunOnUIThread(freeLater);
            try {
                if (context == 0) {
                    context = nInit(modelFile().getAbsolutePath());
                }
                if (context == 0) {
                    return null;
                }
                final int cores = Runtime.getRuntime().availableProcessors();
                final int threads = Math.max(1, Math.min(4, cores - 1));
                final String result = nTranscribe(context, samples, "auto", threads);
                if (result == null) {
                    return null;
                }
                final int nl = result.indexOf('\n');
                final String text = (nl >= 0 ? result.substring(nl + 1) : result).trim();
                return text.isEmpty() ? null : text;
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            } finally {
                // the model stays loaded for a while: the next voice message goes faster
                AndroidUtilities.runOnUIThread(freeLater, 120_000);
            }
        }
    }

    /** Stops a transcription under way (it returns null). */
    public static void abort() {
        if (libraryState == 1) {
            try {
                nAbort();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void freeContext() {
        // the UI thread must not wait for a transcription that is running: try again later
        new Thread(() -> {
            synchronized (lock) {
                if (context != 0) {
                    try {
                        nFree(context);
                    } catch (Throwable ignore) {
                    }
                    context = 0;
                }
            }
        }, "sgWhisperFree").start();
    }
}
