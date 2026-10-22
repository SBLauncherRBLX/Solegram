/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.StatsController;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gemini Nano right on the phone (Android AICore through the ML Kit GenAI Prompt API).
 * Text handed to it never leaves the device. It exists on recent flagships only (Pixel 9 and newer, Galaxy S25/S26,
 * a number of Xiaomi, OnePlus, OPPO, vivo, Honor... phones) with Android 8+; on every other phone this class only
 * reports {@link #STATUS_UNSUPPORTED}. AICore runs requests only while Solegram is on screen and within a per-app
 * quota, so a caller always has a way out (the free service, or an error in the on-device-only mode).
 * Every call to AICore blocks, so nothing here runs on the UI thread.
 */
public final class SolegramGeminiNano {

    /** Not asked yet (the answer comes in a moment). */
    public static final int STATUS_CHECKING = -1;
    /** This phone has no Gemini Nano (or Android has not received the configuration for it yet). */
    public static final int STATUS_UNSUPPORTED = 0;
    /** Supported, but the model is not on the phone yet. */
    public static final int STATUS_DOWNLOADABLE = 1;
    public static final int STATUS_DOWNLOADING = 2;
    public static final int STATUS_AVAILABLE = 3;

    /** Why a request did not run on the device. */
    public static final class NanoException extends Exception {
        public static final int UNAVAILABLE = 0;
        public static final int BUSY = 1;
        public static final int QUOTA = 2;
        public static final int BACKGROUND = 3;
        public static final int TOO_LARGE = 4;
        public static final int LANGUAGE = 5;
        public static final int FAILED = 6;

        public final int reason;

        NanoException(int reason, @Nullable Throwable cause) {
            super("Gemini Nano: " + reason, cause);
            this.reason = reason;
        }
    }

    // a known answer is reused for a while: AICore is a separate app and each question is a binder round trip
    private static final long FRESH_AVAILABLE = 30 * 60_000L;
    private static final long FRESH_UNSUPPORTED = 30 * 60_000L; // "unsupported" may change once Android gets its config
    private static final long FRESH_DOWNLOADABLE = 2 * 60_000L;
    private static final long FRESH_DOWNLOADING = 4_000L;
    /** The client is closed after this much idle time, so AICore can unload the model. */
    private static final long IDLE_CLOSE = 3 * 60_000L;
    private static final long GENERATE_TIMEOUT_SEC = 60;
    private static final long CALL_TIMEOUT_SEC = 8;
    /** The documented input limit of the Prompt API, used while the device has not told its own. */
    private static final int DEFAULT_TOKEN_LIMIT = 4000;

    private static volatile int status = STATUS_CHECKING;
    private static volatile long statusTime;
    private static volatile boolean checkQueued;
    private static volatile long downloadTotal, downloadDone;
    private static volatile boolean downloadRunning;
    private static volatile boolean downloadFailed;
    /** Our download call has returned; its callback may still be reporting. */
    private static volatile boolean downloadDetached;
    private static volatile long quotaUntil;
    private static volatile long pollUntil;
    private static volatile String modelName;

    // status and download questions get their own thread, so a running summary never delays the settings screen
    private static final ExecutorService statusQueue = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SolegramNanoStatus");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private SolegramGeminiNano() {
    }

    /** The Prompt API needs Android 8; on 7.x the library is never touched. */
    public static boolean sdkSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** The last known status (asks AICore again in the background when it is old). Any thread. */
    public static int status() {
        if (!sdkSupported()) {
            return STATUS_UNSUPPORTED;
        }
        if (isStale()) {
            refresh(false);
        }
        return status;
    }

    public static boolean isAvailable() {
        return status() == STATUS_AVAILABLE;
    }

    /** The phone has Nano or can get it (download), as far as is known now. */
    public static boolean isSupported() {
        final int s = status();
        return s == STATUS_AVAILABLE || s == STATUS_DOWNLOADABLE || s == STATUS_DOWNLOADING;
    }

    /** Download progress in percent while this app downloads the model, otherwise -1. */
    public static int downloadPercent() {
        final long total = downloadTotal;
        if (!downloadRunning || total <= 0) {
            return -1;
        }
        return (int) Math.max(0, Math.min(99, downloadDone * 100 / total));
    }

    /** Size of the model download in bytes once AICore told it, otherwise 0. */
    public static long downloadSize() {
        return downloadTotal;
    }

    public static boolean lastDownloadFailed() {
        return downloadFailed;
    }

    /** For example "nano-v3"; null until known. */
    @Nullable
    public static String modelName() {
        return modelName;
    }

    /** AICore said "enough for today": nothing is sent to the device for a while. */
    public static boolean quotaExhausted() {
        return SystemClock.elapsedRealtime() < quotaUntil;
    }

    private static boolean isStale() {
        final long age = SystemClock.elapsedRealtime() - statusTime;
        switch (status) {
            case STATUS_CHECKING:
                return true;
            case STATUS_AVAILABLE:
                return age > FRESH_AVAILABLE;
            case STATUS_DOWNLOADABLE:
                return age > FRESH_DOWNLOADABLE;
            case STATUS_DOWNLOADING:
                return age > FRESH_DOWNLOADING;
            default:
                return age > FRESH_UNSUPPORTED;
        }
    }

    /** Asks AICore for the status in the background; listeners get {@link NotificationCenter#solegramGeminiNanoChanged}. */
    public static void refresh(boolean force) {
        if (!sdkSupported() || checkQueued || !force && !isStale()) {
            return;
        }
        checkQueued = true;
        statusQueue.execute(() -> {
            try {
                checkNow();
            } finally {
                checkQueued = false;
            }
        });
    }

    /** The status, asked right now when the known one is old. Blocks: background threads only. */
    public static int statusBlocking() {
        if (!sdkSupported()) {
            return STATUS_UNSUPPORTED;
        }
        if (isStale()) {
            checkNow();
        }
        return status;
    }

    private static void checkNow() {
        int s;
        try {
            s = Impl.checkStatus();
        } catch (Throwable e) {
            // no AICore, an old one, or a binding failure: the same thing for the user
            FileLog.e(e, false);
            s = STATUS_UNSUPPORTED;
        }
        if (downloadRunning && (s == STATUS_AVAILABLE || downloadDetached && s != STATUS_DOWNLOADING)) {
            // our download ended without its callback saying so
            downloadRunning = false;
            notifyChanged();
        }
        if (s == STATUS_AVAILABLE && modelName == null) {
            try {
                modelName = Impl.baseModelName();
            } catch (Throwable ignore) {
            }
        }
        setStatus(s);
        if (s == STATUS_DOWNLOADING && !downloadRunning) {
            // someone else (another app, the system) downloads it: look again soon while the result matters
            if (pollUntil == 0) {
                pollUntil = SystemClock.elapsedRealtime() + 30 * 60_000L;
            }
            if (SystemClock.elapsedRealtime() < pollUntil) {
                AndroidUtilities.cancelRunOnUIThread(poll);
                AndroidUtilities.runOnUIThread(poll, 5000);
            }
        } else {
            pollUntil = 0;
        }
    }

    private static final Runnable poll = () -> refresh(true);

    private static void setStatus(int s) {
        final boolean changed = status != s;
        status = s;
        statusTime = SystemClock.elapsedRealtime();
        if (changed) {
            notifyChanged();
        }
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramGeminiNanoChanged));
    }

    /** Wi-Fi or another network that costs nothing per megabyte: the model (a few GB) may be fetched without asking. */
    public static boolean onUnmeteredNetwork() {
        try {
            return ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Starts the model download if it is needed and the network is free; returns whether it started. */
    public static boolean downloadIfUnmetered() {
        return status == STATUS_DOWNLOADABLE && onUnmeteredNetwork() && download();
    }

    /** Starts the model download (the user agreed, or the network is unmetered). Any thread. */
    public static boolean download() {
        if (!sdkSupported() || downloadRunning || status != STATUS_DOWNLOADABLE) {
            return false;
        }
        downloadRunning = true;
        downloadDetached = false;
        downloadFailed = false;
        downloadDone = 0;
        statusQueue.execute(() -> {
            try {
                Impl.download();
            } catch (Throwable e) {
                FileLog.e(e, false);
                onDownloadEnd(false);
            }
        });
        notifyChanged();
        return true;
    }

    private static long lastProgressNotify;

    static void onDownloadStarted(long bytes) {
        downloadTotal = bytes;
        downloadRunning = true;
        status = STATUS_DOWNLOADING;
        statusTime = SystemClock.elapsedRealtime();
        notifyChanged();
    }

    static void onDownloadProgress(long bytes) {
        final int before = downloadPercent();
        downloadDone = bytes;
        final long now = SystemClock.elapsedRealtime();
        // a percent at most twice a second: the settings list redraws on each event
        if (downloadPercent() != before && now - lastProgressNotify > 500) {
            lastProgressNotify = now;
            notifyChanged();
        }
    }

    static void onDownloadEnd(boolean ok) {
        if (!downloadRunning) {
            return; // the callback and the future both report the end
        }
        downloadRunning = false;
        downloadFailed = !ok;
        if (ok) {
            setStatus(STATUS_AVAILABLE);
            statusQueue.execute(() -> {
                try {
                    modelName = Impl.baseModelName();
                } catch (Throwable ignore) {
                }
            });
        } else {
            statusTime = 0; // ask again: it may have failed only for the network
            refresh(true);
        }
        notifyChanged();
    }

    /**
     * Runs one prompt on the device and returns the answer. Blocks for up to a minute: background threads only.
     *
     * @param instruction how to answer (a system instruction where the model supports one)
     * @param input       the text to work on, with any reminder that must follow it
     */
    public static String generate(String instruction, String input, int maxOutputTokens) throws NanoException {
        if (!sdkSupported()) {
            throw new NanoException(NanoException.UNAVAILABLE, null);
        }
        if (quotaExhausted()) {
            throw new NanoException(NanoException.QUOTA, null);
        }
        if (statusBlocking() != STATUS_AVAILABLE) {
            throw new NanoException(NanoException.UNAVAILABLE, null);
        }
        Impl.inFlight(true);
        try {
            // AICore asks to slow down now and then: two more tries with a growing pause
            for (int attempt = 0; ; attempt++) {
                try {
                    return Impl.generate(instruction, input, maxOutputTokens);
                } catch (NanoException e) {
                    if (e.reason != NanoException.BUSY || attempt >= 2) {
                        throw e;
                    }
                    try {
                        Thread.sleep(1500L << attempt);
                    } catch (InterruptedException ie) {
                        throw e;
                    }
                }
            }
        } finally {
            Impl.inFlight(false);
        }
    }

    /** Tokens the request would take (instruction included), or -1 when the device does not say. Background threads only. */
    public static int countTokens(String instruction, String input) {
        if (!sdkSupported()) {
            return -1;
        }
        Impl.inFlight(true);
        try {
            return Impl.countTokens(instruction, input);
        } catch (Throwable e) {
            return -1;
        } finally {
            Impl.inFlight(false);
        }
    }

    /** The input limit of the model on this phone, in tokens. Background threads only. */
    public static int tokenLimit() {
        if (!sdkSupported()) {
            return DEFAULT_TOKEN_LIMIT;
        }
        Impl.inFlight(true);
        try {
            final int limit = Impl.tokenLimit();
            return limit > 0 ? limit : DEFAULT_TOKEN_LIMIT;
        } catch (Throwable e) {
            return DEFAULT_TOKEN_LIMIT;
        } finally {
            Impl.inFlight(false);
        }
    }

    /** Everything that touches the ML Kit classes (Android 8+ only, so 7.x never even loads them). */
    private static final class Impl {

        private static com.google.mlkit.genai.prompt.java.GenerativeModelFutures client;
        private static Boolean systemPrompt;
        private static int tokenLimit;
        private static int inFlight;
        private static final Runnable closeIdle = Impl::closeIfIdle;

        private static synchronized com.google.mlkit.genai.prompt.java.GenerativeModelFutures client() {
            AndroidUtilities.cancelRunOnUIThread(closeIdle);
            if (client == null) {
                try {
                    com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ApplicationLoader.applicationContext);
                } catch (Throwable ignore) {
                }
                client = com.google.mlkit.genai.prompt.java.GenerativeModelFutures.from(com.google.mlkit.genai.prompt.Generation.INSTANCE.getClient());
            }
            return client;
        }

        static synchronized void inFlight(boolean started) {
            inFlight += started ? 1 : -1;
            if (inFlight <= 0) {
                inFlight = 0;
                AndroidUtilities.cancelRunOnUIThread(closeIdle);
                AndroidUtilities.runOnUIThread(closeIdle, IDLE_CLOSE);
            }
        }

        private static void closeIfIdle() {
            statusQueue.execute(() -> {
                synchronized (Impl.class) {
                    if (inFlight > 0 || client == null || downloadRunning) {
                        return;
                    }
                    try {
                        client.getGenerativeModel().close();
                    } catch (Throwable e) {
                        FileLog.e(e, false);
                    }
                    client = null;
                }
            });
        }

        static int checkStatus() throws Exception {
            final int s = client().checkStatus().get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
            scheduleClose();
            switch (s) {
                case com.google.mlkit.genai.common.FeatureStatus.AVAILABLE:
                    return STATUS_AVAILABLE;
                case com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE:
                    return STATUS_DOWNLOADABLE;
                case com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING:
                    return STATUS_DOWNLOADING;
                default:
                    return STATUS_UNSUPPORTED;
            }
        }

        private static synchronized void scheduleClose() {
            if (inFlight == 0) {
                AndroidUtilities.cancelRunOnUIThread(closeIdle);
                AndroidUtilities.runOnUIThread(closeIdle, IDLE_CLOSE);
            }
        }

        static String baseModelName() throws Exception {
            return client().getBaseModelName().get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
        }

        static void download() {
            final com.google.common.util.concurrent.ListenableFuture<Void> future = client().download(new com.google.mlkit.genai.common.DownloadCallback() {
                @Override
                public void onDownloadStarted(long bytesToDownload) {
                    SolegramGeminiNano.onDownloadStarted(bytesToDownload);
                }

                @Override
                public void onDownloadProgress(long totalBytesDownloaded) {
                    SolegramGeminiNano.onDownloadProgress(totalBytesDownloaded);
                }

                @Override
                public void onDownloadCompleted() {
                    SolegramGeminiNano.onDownloadEnd(true);
                    scheduleClose();
                }

                @Override
                public void onDownloadFailed(com.google.mlkit.genai.common.GenAiException e) {
                    FileLog.e(e, false);
                    SolegramGeminiNano.onDownloadEnd(false);
                    scheduleClose();
                }
            });
            // the future ends too, also when the callback stays silent (AICore went away mid-download)
            future.addListener(() -> {
                try {
                    future.get();
                    // the status says how it really ended
                    downloadDetached = true;
                    statusTime = 0;
                    refresh(true);
                } catch (Throwable e) {
                    SolegramGeminiNano.onDownloadEnd(false);
                }
                scheduleClose();
            }, statusQueue);
        }

        private static boolean systemPromptAvailable(com.google.mlkit.genai.prompt.java.GenerativeModelFutures model) {
            Boolean b = systemPrompt;
            if (b == null) {
                try {
                    b = model.isSystemPromptAvailable().get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (Throwable e) {
                    b = false;
                }
                systemPrompt = b;
            }
            return b;
        }

        private static com.google.mlkit.genai.prompt.GenerateContentRequest request(com.google.mlkit.genai.prompt.java.GenerativeModelFutures model, String instruction, String input, int maxOutputTokens) {
            final com.google.mlkit.genai.prompt.GenerateContentRequest.Builder builder;
            if (systemPromptAvailable(model)) {
                builder = new com.google.mlkit.genai.prompt.GenerateContentRequest.Builder(
                    new com.google.mlkit.genai.prompt.SystemInstruction(instruction), new com.google.mlkit.genai.prompt.TextPart(input));
            } else {
                // older Nano (v2) has no system instructions: they go first in the prompt itself
                builder = new com.google.mlkit.genai.prompt.GenerateContentRequest.Builder(
                    new com.google.mlkit.genai.prompt.TextPart(instruction + "\n\n" + input));
            }
            // a summary should stick to the text, not be creative
            builder.setTemperature(0.2f);
            builder.setTopK(16);
            builder.setCandidateCount(1);
            if (maxOutputTokens > 0) {
                builder.setMaxOutputTokens(maxOutputTokens);
            }
            return builder.build();
        }

        static String generate(String instruction, String input, int maxOutputTokens) throws NanoException {
            final com.google.mlkit.genai.prompt.java.GenerativeModelFutures model = client();
            try {
                final com.google.mlkit.genai.prompt.GenerateContentResponse response = model.generateContent(request(model, instruction, input, maxOutputTokens))
                    .get(GENERATE_TIMEOUT_SEC, TimeUnit.SECONDS);
                final List<com.google.mlkit.genai.prompt.Candidate> candidates = response.getCandidates();
                final String text = candidates == null || candidates.isEmpty() ? null : candidates.get(0).getText();
                if (TextUtils.isEmpty(text)) {
                    throw new NanoException(NanoException.FAILED, null);
                }
                return text;
            } catch (ExecutionException e) {
                throw map(e.getCause() != null ? e.getCause() : e);
            } catch (TimeoutException e) {
                throw new NanoException(NanoException.FAILED, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NanoException(NanoException.FAILED, e);
            } catch (NanoException e) {
                throw e;
            } catch (Throwable e) {
                throw map(e);
            }
        }

        private static NanoException map(Throwable e) {
            if (!(e instanceof com.google.mlkit.genai.common.GenAiException)) {
                FileLog.e(e, false);
                return new NanoException(NanoException.FAILED, e);
            }
            final int code = ((com.google.mlkit.genai.common.GenAiException) e).getErrorCode();
            switch (code) {
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY:
                    return new NanoException(NanoException.BUSY, e);
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED:
                    // a long-term (daily) limit: an hour without asking AICore, then try again
                    quotaUntil = SystemClock.elapsedRealtime() + 60 * 60_000L;
                    return new NanoException(NanoException.QUOTA, e);
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED:
                    return new NanoException(NanoException.BACKGROUND, e);
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.REQUEST_TOO_LARGE:
                    return new NanoException(NanoException.TOO_LARGE, e);
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.NOT_AVAILABLE:
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.NOT_SUPPORTED:
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.AICORE_INCOMPATIBLE:
                case com.google.mlkit.genai.common.GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE:
                    // the model went away (updated, removed): ask again before the next request
                    statusTime = 0;
                    refresh(true);
                    return new NanoException(NanoException.UNAVAILABLE, e);
                default:
                    FileLog.e(e, false);
                    return new NanoException(NanoException.FAILED, e);
            }
        }

        static int countTokens(String instruction, String input) throws Exception {
            final com.google.mlkit.genai.prompt.java.GenerativeModelFutures model = client();
            return model.countTokens(request(model, instruction, input, 0)).get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS).getTotalTokens();
        }

        static int tokenLimit() throws Exception {
            if (tokenLimit <= 0) {
                final Integer limit = client().getTokenLimit().get(CALL_TIMEOUT_SEC, TimeUnit.SECONDS);
                tokenLimit = limit != null ? limit : 0;
            }
            return tokenLimit;
        }
    }
}
