/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transcription of voice messages and round videos without Telegram Premium, shown in Telegram's own
 * transcription UI (TranscribeButton hands the message over and gets the text back). Engines:
 * - the phone's speech recognizer (Android 13+): the audio is decoded here to 16 kHz PCM and streamed to it,
 *   so with an on-device recognizer nothing leaves the phone;
 * - the user's own OpenAI-compatible service (POST /audio/transcriptions, e.g. OpenAI or Groq Whisper).
 * The free AI service (Pollinations) has no speech-to-text, so without the user's own service only the phone is used.
 * One transcription runs at a time (a recognizer serves one session); the others wait in a short queue.
 */
public final class SolegramTranscriber {

    public static final int ENGINE_AUTO = 0;
    public static final int ENGINE_DEVICE = 1;
    public static final int ENGINE_AI = 2;

    private static final int SAMPLE_RATE = 16000;
    /** 100 ms of audio: the unit for finding pauses. */
    private static final int WINDOW = SAMPLE_RATE / 10;
    private static final int MAX_DEVICE_SECONDS = 30 * 60;
    /** OpenAI and Groq take files up to 25 MB. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    private static final int MAX_QUEUE = 20;
    private static final long DOWNLOAD_TIMEOUT = 2 * 60_000;
    /** A recognizer that says nothing for this long is stuck. */
    private static final long RECOGNIZER_TIMEOUT = 45_000;

    /** Results come on the UI thread. */
    public interface Callback {
        /** Text heard so far, while the transcription goes on. */
        void onPartial(String text);

        /** The final text; empty when no words were recognized. */
        void onDone(String text);

        /** The transcription stopped (cancelled, or failed and the user was told why). */
        void onStopped();
    }

    private SolegramTranscriber() {
    }

    // region Which messages

    /**
     * Whether Solegram transcribes this message instead of Telegram's server. Premium users keep Telegram's
     * transcription unless they picked an engine explicitly; in "Auto" the phone must be able to do it (or the
     * user's own AI service is set up), otherwise Telegram's own behavior (trial or Premium offer) stays.
     */
    public static boolean handles(MessageObject m) {
        if (!SolegramConfig.transcribe || m == null || m.messageOwner == null || !(m.isVoice() || m.isRoundVideo())) {
            return false;
        }
        if (SolegramConfig.transcribeEngine != ENGINE_AUTO) {
            return true;
        }
        if (UserConfig.getInstance(m.currentAccount).isPremium() || TranscribeButton.isFreeTranscribeInChat(m)) {
            return false;
        }
        return deviceAvailable() || autoAiAllowed(m);
    }

    private static long availabilityCheckedAt;
    private static boolean recognizerAvailable;

    /** A speech recognizer the app can stream audio to (Android 13+). Cached: it is asked on every voice bubble. */
    public static boolean deviceAvailable() {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        final long now = SystemClock.elapsedRealtime();
        if (availabilityCheckedAt == 0 || now - availabilityCheckedAt > 10 * 60_000) {
            availabilityCheckedAt = now;
            try {
                final Context context = ApplicationLoader.applicationContext;
                recognizerAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context);
            } catch (Throwable e) {
                recognizerAvailable = false;
            }
        }
        return recognizerAvailable;
    }

    /** Forget the cached answer (the settings screen asks again when it opens). */
    public static void refreshAvailability() {
        availabilityCheckedAt = 0;
    }

    public static boolean aiServiceSet() {
        return !TextUtils.isEmpty(SolegramConfig.aiBaseUrl);
    }

    /** "Auto" sends audio out only to the user's own service, chosen for AI features in settings. */
    private static boolean autoAiAllowed(MessageObject m) {
        return SolegramConfig.aiProvider == SolegramSummarizer.PROVIDER_OWN && aiServiceSet() && !isProtected(m);
    }

    /** Secret chats and chats that forbid forwarding: their audio is never sent to an outside service. */
    private static boolean isProtected(MessageObject m) {
        final long dialogId = m.getDialogId();
        return DialogObject.isEncryptedDialog(dialogId) || m.messageOwner.noforwards
            || MessagesController.getInstance(m.currentAccount).isPeerNoForwards(dialogId);
    }

    private static int[] engines(MessageObject m) {
        switch (SolegramConfig.transcribeEngine) {
            case ENGINE_DEVICE:
                return new int[]{ENGINE_DEVICE};
            case ENGINE_AI:
                return new int[]{ENGINE_AI};
            default:
                final boolean device = deviceAvailable(), ai = autoAiAllowed(m);
                if (device && ai) {
                    return new int[]{ENGINE_DEVICE, ENGINE_AI};
                }
                return device ? new int[]{ENGINE_DEVICE} : ai ? new int[]{ENGINE_AI} : new int[0];
        }
    }

    // ids of Solegram transcriptions: negative, so they never meet the ids of Telegram's server transcriptions
    private static final AtomicLong ids = new AtomicLong(-System.currentTimeMillis() * 1000);

    public static long newTranscriptionId() {
        return ids.decrementAndGet();
    }
    // endregion

    // region Queue (UI thread)

    private static final class Job {
        final MessageObject message;
        final Callback callback;
        final int account;
        final long dialogId;
        final int messageId;
        volatile boolean cancelled;
        boolean finished;
        int[] engines;
        int engineIndex;
        File file;
        /** Stops whatever runs for the job right now (download wait, recognizer, request). */
        Runnable stopper;

        Job(MessageObject message, Callback callback) {
            this.message = message;
            this.callback = callback;
            this.account = message.currentAccount;
            this.dialogId = message.getDialogId();
            this.messageId = message.getId();
        }

        boolean is(int account, long dialogId, int messageId) {
            return this.account == account && this.dialogId == dialogId && this.messageId == messageId;
        }

        boolean dead() {
            return cancelled || finished;
        }
    }

    private static final ArrayList<Job> queue = new ArrayList<>();
    private static Job active;
    // decoding and uploads; jobs run one at a time anyway
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** Starts (or queues) the transcription of a message; a message already in work is not taken twice. */
    public static void transcribe(MessageObject m, Callback callback) {
        if (find(m) != null) {
            return;
        }
        if (queue.size() >= MAX_QUEUE) {
            AndroidUtilities.runOnUIThread(() -> {
                callback.onStopped();
                showError(LocaleController.getString(R.string.SG_TranscribeQueueFull));
            });
            return;
        }
        queue.add(new Job(m, callback));
        next();
    }

    public static boolean isRunning(MessageObject m) {
        return find(m) != null;
    }

    /** The user closed the transcription: it stops quietly. */
    public static void cancel(MessageObject m) {
        final Job job = find(m);
        if (job != null) {
            stop(job);
        }
    }

    /** The chat was closed: its transcriptions stop. */
    public static void cancelDialog(int account, long dialogId) {
        for (Job job : new ArrayList<>(queue)) {
            if (job.account == account && job.dialogId == dialogId) {
                stop(job);
            }
        }
        if (active != null && active.account == account && active.dialogId == dialogId) {
            stop(active);
        }
    }

    @Nullable
    private static Job find(MessageObject m) {
        if (m == null) {
            return null;
        }
        final long dialogId = m.getDialogId();
        final int id = m.getId();
        if (active != null && active.is(m.currentAccount, dialogId, id)) {
            return active;
        }
        for (Job job : queue) {
            if (job.is(m.currentAccount, dialogId, id)) {
                return job;
            }
        }
        return null;
    }

    private static void stop(Job job) {
        if (job.dead()) {
            return;
        }
        job.cancelled = true;
        queue.remove(job);
        if (job.stopper != null) {
            job.stopper.run();
            job.stopper = null;
        }
        job.callback.onStopped();
        if (active == job) {
            active = null;
            next();
        }
    }

    private static void next() {
        if (active != null || queue.isEmpty()) {
            return;
        }
        final Job job = active = queue.remove(0);
        job.engines = engines(job.message);
        if (job.engines.length == 0) {
            fail(job, LocaleController.getString(Build.VERSION.SDK_INT < 33 ? R.string.SG_TranscribeOldAndroid : R.string.SG_TranscribeNoRecognizer), null);
            return;
        }
        // the engines that need the file decide alone whether they can run, the file comes first
        resolveFile(job);
    }

    private static void complete(Job job, String text) {
        if (job.dead()) {
            return;
        }
        job.finished = true;
        job.stopper = null;
        if (active == job) {
            active = null;
        }
        job.callback.onDone(text == null ? "" : text.trim());
        next();
    }

    /** @param language a language whose recognition pack is missing on the phone (offered for download), or null */
    private static void fail(Job job, String error, @Nullable String language) {
        if (job.dead()) {
            return;
        }
        job.finished = true;
        job.stopper = null;
        if (active == job) {
            active = null;
        }
        job.callback.onStopped();
        if (language != null && Build.VERSION.SDK_INT >= 33) {
            showLanguageDialog(error, language);
        } else {
            showError(error);
        }
        next();
    }

    /** One engine could not do it: the next one in "Auto" tries, or the user is told. */
    private static void engineFailed(Job job, String error, @Nullable String language) {
        if (job.dead()) {
            return;
        }
        job.stopper = null;
        if (job.engineIndex + 1 < job.engines.length) {
            job.engineIndex++;
            runEngine(job);
        } else {
            fail(job, error, language);
        }
    }

    private static void runEngine(Job job) {
        if (job.engines[job.engineIndex] == ENGINE_AI) {
            startAi(job);
        } else if (Build.VERSION.SDK_INT >= 33) {
            startDevice(job);
        } else {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeOldAndroid), null);
        }
    }
    // endregion

    // region The audio file

    private static void resolveFile(Job job) {
        worker.execute(() -> {
            final File file = localFile(job.message);
            AndroidUtilities.runOnUIThread(() -> {
                if (job.dead()) {
                    return;
                }
                if (file != null) {
                    job.file = file;
                    runEngine(job);
                } else {
                    download(job);
                }
            });
        });
    }

    @Nullable
    private static File localFile(MessageObject m) {
        try {
            if (!TextUtils.isEmpty(m.messageOwner.attachPath)) {
                final File f = new File(m.messageOwner.attachPath);
                if (f.exists() && f.length() > 0) {
                    return f;
                }
            }
            final File f = FileLoader.getInstance(m.currentAccount).getPathToMessage(m.messageOwner);
            if (f != null && f.exists() && f.length() > 0) {
                return f;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** Voice messages are usually downloaded already; otherwise the transcription waits for the file. */
    private static void download(Job job) {
        final TLRPC.Document document = job.message.getDocument();
        if (document == null) {
            fail(job, LocaleController.getString(R.string.SG_TranscribeDownloadFailed), null);
            return;
        }
        final String name = FileLoader.getAttachFileName(document);
        final NotificationCenter center = NotificationCenter.getInstance(job.account);
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        final Runnable[] timeout = new Runnable[1];
        final Runnable remove = () -> {
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            AndroidUtilities.cancelRunOnUIThread(timeout[0]);
        };
        observer[0] = (id, account, args) -> {
            if (args.length == 0 || !name.equals(args[0])) {
                return;
            }
            remove.run();
            if (job.dead()) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                final File loaded = args.length > 1 && args[1] instanceof File ? (File) args[1] : null;
                if (loaded != null && loaded.exists()) {
                    job.file = loaded;
                    runEngine(job);
                } else {
                    resolveAfterDownload(job);
                }
            } else {
                fail(job, LocaleController.getString(R.string.SG_TranscribeDownloadFailed), null);
            }
        };
        timeout[0] = () -> {
            remove.run();
            fail(job, LocaleController.getString(R.string.SG_TranscribeDownloadFailed), null);
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        AndroidUtilities.runOnUIThread(timeout[0], DOWNLOAD_TIMEOUT);
        job.stopper = remove;
        FileLoader.getInstance(job.account).loadFile(document, job.message, FileLoader.PRIORITY_HIGH, 0);
    }

    private static void resolveAfterDownload(Job job) {
        worker.execute(() -> {
            final File file = localFile(job.message);
            AndroidUtilities.runOnUIThread(() -> {
                if (job.dead()) {
                    return;
                }
                if (file != null) {
                    job.file = file;
                    runEngine(job);
                } else {
                    fail(job, LocaleController.getString(R.string.SG_TranscribeDownloadFailed), null);
                }
            });
        });
    }
    // endregion

    // region Errors

    private static void showError(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        try {
            BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The phone's recognizer lacks the language: offer to download its pack (or open the voice input settings). */
    @RequiresApi(33)
    private static void showLanguageDialog(String text, String language) {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            showError(text);
            return;
        }
        final Context context = fragment.getParentActivity();
        final AlertDialog dialog = new AlertDialog.Builder(context, fragment.getResourceProvider())
            .setTitle(LocaleController.getString(R.string.SG_TranscribeHeader))
            .setMessage(text)
            .setPositiveButton(LocaleController.getString(R.string.SG_TranscribeDownload), (d, w) -> downloadLanguage(context, language))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        fragment.showDialog(dialog);
    }

    @RequiresApi(33)
    private static void downloadLanguage(Context context, String language) {
        try {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                final SpeechRecognizer recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                recognizer.triggerModelDownload(intent);
                // the request goes to the service asynchronously; the recognizer is released a bit later
                AndroidUtilities.runOnUIThread(recognizer::destroy, 10_000);
                showError(LocaleController.getString(R.string.SG_TranscribeDownloadStarted));
                return;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            final Intent intent = new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The app language for the recognizer, with the region of the phone when it speaks the same language. */
    private static String recognitionLanguage() {
        Locale app = LocaleController.getInstance().getCurrentLocale();
        final Locale system = LocaleController.getInstance().getSystemDefaultLocale();
        if (app == null) {
            app = system != null ? system : Locale.getDefault();
        }
        if (TextUtils.isEmpty(app.getCountry()) && system != null && app.getLanguage().equals(system.getLanguage()) && !TextUtils.isEmpty(system.getCountry())) {
            app = system;
        }
        if (TextUtils.isEmpty(app.getCountry())) {
            // recognizers name their languages with a region ("en-US"): the most likely one is added
            try {
                final String country = android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLocale(app)).getCountry();
                if (!TextUtils.isEmpty(country)) {
                    app = new Locale(app.getLanguage(), country);
                }
            } catch (Throwable ignore) {
            }
        }
        return app.toLanguageTag();
    }

    private static String languageName(String tag) {
        final Locale locale = Locale.forLanguageTag(tag);
        final String name = locale.getDisplayLanguage(LocaleController.getInstance().getCurrentLocale() != null ? LocaleController.getInstance().getCurrentLocale() : Locale.getDefault());
        return TextUtils.isEmpty(name) ? tag : name;
    }
    // endregion

    // region On the device

    // the recognizer ignored a segmented session once: later ones go phrase by phrase from the start
    private static boolean segmentedUnsupported;

    @RequiresApi(33)
    private static void startDevice(Job job) {
        if (!deviceAvailable()) {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeNoRecognizer), null);
            return;
        }
        if (job.message.getDuration() > MAX_DEVICE_SECONDS) {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeTooLong), null);
            return;
        }
        final File source = job.file;
        worker.execute(() -> {
            Decoded decoded = null;
            try {
                decoded = decode(source, job);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Decoded audio = decoded;
            AndroidUtilities.runOnUIThread(() -> {
                if (job.dead()) {
                    if (audio != null) {
                        audio.delete();
                    }
                    return;
                }
                if (audio == null) {
                    engineFailed(job, LocaleController.getString(R.string.SG_TranscribeFailed), null);
                    return;
                }
                new DeviceRun(job, audio).begin();
            });
        });
    }

    /** 16 kHz mono 16-bit PCM in a private cache file, and the loudness of every 100 ms. */
    private static final class Decoded {
        final File pcm;
        final int samples;
        final float[] loudness;

        Decoded(File pcm, int samples, float[] loudness) {
            this.pcm = pcm;
            this.samples = samples;
            this.loudness = loudness;
        }

        void delete() {
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
        }
    }

    /** Opus in OGG (voice) and AAC in MP4 (round videos) through the system decoders. */
    @RequiresApi(33)
    @Nullable
    private static Decoded decode(File source, Job job) throws Exception {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sg_transcribe");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // left over when the app was closed in the middle of a transcription
        final File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (System.currentTimeMillis() - f.lastModified() > 60 * 60_000) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        final File out = new File(dir, job.messageId + "_" + SystemClock.elapsedRealtimeNanos() + ".pcm");
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean ok = false;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 64 * 1024)) {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = f;
                    break;
                }
            }
            if (format == null) {
                throw new IOException("no audio track");
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            final Converter converter = new Converter(os);
            converter.setFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), AudioFormat.ENCODING_PCM_16BIT);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long maxSamples = (long) MAX_DEVICE_SECONDS * SAMPLE_RATE;
            boolean inputDone = false;
            while (true) {
                if (job.cancelled) {
                    return null;
                }
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = codec.getOutputFormat();
                    converter.setFormat(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        f.containsKey(MediaFormat.KEY_PCM_ENCODING) ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT);
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        final ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            converter.feed(buffer.slice().order(ByteOrder.nativeOrder()));
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || converter.samples >= maxSamples) {
                        break;
                    }
                }
            }
            converter.finish();
            ok = true;
            return new Decoded(out, (int) converter.samples, converter.loudness());
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                codec.release();
            }
            extractor.release();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }
    }

    /** Decoder output (any rate, channels, 16-bit or float) to 16 kHz mono 16-bit little-endian. */
    private static final class Converter {
        private final OutputStream os;
        private final ByteBuffer out = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        private int channels = 1, encoding = AudioFormat.ENCODING_PCM_16BIT;
        private double step = 1, t;
        private float previous;
        private Biquad lowPass1, lowPass2;
        private float[] mono = new float[0];
        long samples;
        private double windowSum;
        private int windowCount;
        private float[] windows = new float[256];
        private int windowsSize;

        Converter(OutputStream os) {
            this.os = os;
        }

        void setFormat(int rate, int channels, int encoding) {
            this.channels = Math.max(1, channels);
            this.encoding = encoding;
            this.step = rate / (double) SAMPLE_RATE;
            // a higher source rate is filtered first, so what is above 8 kHz does not fold into speech
            if (rate > SAMPLE_RATE) {
                lowPass1 = new Biquad(rate, 7000);
                lowPass2 = new Biquad(rate, 7000);
            } else {
                lowPass1 = lowPass2 = null;
            }
        }

        void feed(ByteBuffer in) throws IOException {
            final boolean isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            final int frames = in.remaining() / (isFloat ? 4 : 2) / channels;
            if (frames <= 0) {
                return;
            }
            if (mono.length < frames) {
                mono = new float[frames];
            }
            for (int f = 0; f < frames; f++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += isFloat ? in.getFloat() : in.getShort() / 32768f;
                }
                float v = sum / channels;
                if (lowPass1 != null) {
                    v = lowPass2.process(lowPass1.process(v));
                }
                mono[f] = v;
            }
            // linear interpolation; t is the next output position, counted from the start of this buffer
            while (t < frames - 1) {
                final int i = (int) Math.floor(t);
                final double frac = t - i;
                final float a = i < 0 ? previous : mono[i];
                final float b = mono[i + 1];
                emit((float) (a + (b - a) * frac));
                t += step;
            }
            t -= frames;
            previous = mono[frames - 1];
        }

        private void emit(float v) throws IOException {
            v = Math.max(-1f, Math.min(1f, v));
            out.putShort((short) (v * 32767));
            if (!out.hasRemaining()) {
                os.write(out.array(), 0, out.position());
                out.clear();
            }
            samples++;
            windowSum += v * v;
            if (++windowCount == WINDOW) {
                if (windowsSize == windows.length) {
                    windows = Arrays.copyOf(windows, windows.length * 2);
                }
                windows[windowsSize++] = (float) Math.sqrt(windowSum / WINDOW);
                windowSum = 0;
                windowCount = 0;
            }
        }

        void finish() throws IOException {
            if (out.position() > 0) {
                os.write(out.array(), 0, out.position());
                out.clear();
            }
            if (windowCount > 0) {
                if (windowsSize == windows.length) {
                    windows = Arrays.copyOf(windows, windows.length + 1);
                }
                windows[windowsSize++] = (float) Math.sqrt(windowSum / windowCount);
                windowSum = 0;
                windowCount = 0;
            }
        }

        float[] loudness() {
            return Arrays.copyOf(windows, windowsSize);
        }
    }

    /** Second-order low-pass (RBJ cookbook). */
    private static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        Biquad(int rate, int cutoff) {
            final double w0 = 2 * Math.PI * cutoff / rate;
            final double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
            final double a0 = 1 + alpha;
            b0 = (float) ((1 - cos) / 2 / a0);
            b1 = (float) ((1 - cos) / a0);
            b2 = b0;
            a1 = (float) (-2 * cos / a0);
            a2 = (float) ((1 - alpha) / a0);
        }

        float process(float x) {
            final float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }

    /**
     * Phrases of the audio (sample ranges), cut in the middle of pauses of 0.6 s or more and at most 30 s long.
     * Used when the recognizer cannot take the whole message as one segmented session. Empty when all is silence.
     */
    private static List<int[]> phrases(Decoded audio) {
        final float[] loud = audio.loudness;
        final int n = loud.length;
        final ArrayList<int[]> result = new ArrayList<>();
        if (n == 0) {
            return result;
        }
        final float[] sorted = loud.clone();
        Arrays.sort(sorted);
        final float noise = sorted[(int) (n * 0.1f)];
        final float speech = sorted[Math.min(n - 1, (int) (n * 0.95f))];
        if (speech < 0.003f) {
            // quieter than -50 dBFS all the way: nothing to recognize
            return result;
        }
        final float threshold = noise + (speech - noise) * 0.12f;
        final ArrayList<int[]> windows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < n; ) {
            if (loud[i] < threshold) {
                int j = i;
                while (j < n && loud[j] < threshold) {
                    j++;
                }
                if (j - i >= 6 && i > start && j < n) {
                    final int cut = (i + j) / 2;
                    windows.add(new int[]{start, cut});
                    start = cut;
                }
                i = j;
            } else {
                i++;
            }
        }
        windows.add(new int[]{start, n});
        final int max = 300, min = 150;
        for (int[] w : windows) {
            int from = w[0];
            // a long stretch without pauses is cut at its quietest moment
            while (w[1] - from > max) {
                int cut = from + min;
                for (int k = from + min; k < from + max; k++) {
                    if (loud[k] < loud[cut]) {
                        cut = k;
                    }
                }
                addPhrase(result, loud, threshold, from, cut, audio.samples);
                from = cut;
            }
            addPhrase(result, loud, threshold, from, w[1], audio.samples);
        }
        return result;
    }

    private static void addPhrase(List<int[]> result, float[] loud, float threshold, int from, int to, int samples) {
        for (int k = from; k < to; k++) {
            if (loud[k] >= threshold) {
                result.add(new int[]{from * WINDOW, Math.min(samples, to * WINDOW)});
                return;
            }
        }
    }

    /** One feeding of PCM into the recognizer's pipe, on its own thread; the write end is non-blocking so it always ends. */
    private static final class Feed implements Runnable {
        final ParcelFileDescriptor readEnd, writeEnd;
        final File pcm;
        final long from, to;
        volatile boolean closed;

        Feed(ParcelFileDescriptor[] pipe, File pcm, int fromSample, int toSample) {
            this.readEnd = pipe[0];
            this.writeEnd = pipe[1];
            this.pcm = pcm;
            this.from = fromSample * 2L;
            this.to = toSample * 2L;
        }

        @Override
        public void run() {
            try (RandomAccessFile in = new RandomAccessFile(pcm, "r")) {
                final FileDescriptor fd = writeEnd.getFileDescriptor();
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
                final byte[] buffer = new byte[6400];
                in.seek(from);
                long position = from;
                // half a second of silence at the end, so the last word is heard to its end
                long tail = SAMPLE_RATE;
                while (!closed) {
                    int n;
                    if (position < to) {
                        n = in.read(buffer, 0, (int) Math.min(buffer.length, to - position));
                        if (n <= 0) {
                            position = to;
                            continue;
                        }
                        position += n;
                    } else if (tail > 0) {
                        n = (int) Math.min(buffer.length, tail);
                        Arrays.fill(buffer, 0, n, (byte) 0);
                        tail -= n;
                    } else {
                        break;
                    }
                    int offset = 0;
                    while (offset < n && !closed) {
                        try {
                            offset += Os.write(fd, buffer, offset, n - offset);
                        } catch (ErrnoException e) {
                            if (e.errno != OsConstants.EAGAIN) {
                                // EPIPE: the recognizer stopped reading
                                return;
                            }
                            Thread.sleep(15);
                        }
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                // end of the stream: the recognizer finishes the session
                closeQuietly(writeEnd);
            }
        }

        void close() {
            closed = true;
            closeQuietly(readEnd);
        }
    }

    private static void closeQuietly(@Nullable ParcelFileDescriptor fd) {
        if (fd != null) {
            try {
                fd.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * The phone's recognizer over the decoded audio, on the UI thread (SpeechRecognizer must live there).
     * First the whole message as one segmented session; a recognizer that does not do segmented sessions
     * stops after the first phrase, and then the message goes phrase by phrase.
     */
    @RequiresApi(33)
    private static final class DeviceRun implements RecognitionListener {
        private final Job job;
        private final Decoded audio;
        private final List<int[]> phrases;
        private final String language;
        private SpeechRecognizer recognizer;
        private boolean onDevice;
        private Feed feed;
        private boolean sessionOpen, segmented, released;
        private int phrase, segments, busyRetries;
        private final StringBuilder text = new StringBuilder();
        private String lastSegment, partial;
        private long lastActivity, lastPublished;
        private final Runnable watchdog = this::checkStuck;

        DeviceRun(Job job, Decoded audio) {
            this.job = job;
            this.audio = audio;
            this.phrases = phrases(audio);
            this.language = recognitionLanguage();
        }

        void begin() {
            job.stopper = this::release;
            if (phrases.isEmpty()) {
                release();
                complete(job, "");
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            try {
                onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
                recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context) : SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
            } catch (Throwable e) {
                FileLog.e(e);
                failed(LocaleController.getString(R.string.SG_TranscribeNoRecognizer), null);
                return;
            }
            lastActivity = SystemClock.elapsedRealtime();
            AndroidUtilities.runOnUIThread(watchdog, 5000);
            if (segmentedUnsupported && phrases.size() > 1) {
                startPhrase(0);
            } else {
                startSession(0, audio.samples, true);
            }
        }

        private void startPhrase(int index) {
            phrase = index;
            final int[] range = phrases.get(index);
            startSession(range[0], range[1], false);
        }

        private void startSession(int from, int to, boolean segmentedSession) {
            if (released) {
                return;
            }
            closeSession();
            final ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                FileLog.e(e);
                failed(LocaleController.getString(R.string.SG_TranscribeFailed), null);
                return;
            }
            segmented = segmentedSession;
            partial = null;
            final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ApplicationLoader.applicationContext.getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
            if (segmentedSession) {
                // results come phrase by phrase until the stream ends, instead of stopping at the first pause
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
            }
            feed = new Feed(pipe, audio.pcm, from, to);
            sessionOpen = true;
            try {
                recognizer.startListening(intent);
            } catch (Throwable e) {
                FileLog.e(e);
                closeQuietly(pipe[1]);
                failed(LocaleController.getString(R.string.SG_TranscribeFailed), null);
                return;
            }
            final Thread thread = new Thread(feed, "SolegramTranscribeFeed");
            thread.setDaemon(true);
            thread.start();
            touch();
        }

        /** Ends the current session's pipe (our read end; the feeding thread closes the write end). */
        private void closeSession() {
            sessionOpen = false;
            if (feed != null) {
                feed.close();
                feed = null;
            }
        }

        private void touch() {
            lastActivity = SystemClock.elapsedRealtime();
        }

        private void checkStuck() {
            if (released) {
                return;
            }
            if (SystemClock.elapsedRealtime() - lastActivity > RECOGNIZER_TIMEOUT) {
                failed(LocaleController.getString(R.string.SG_TranscribeFailed), null);
                return;
            }
            AndroidUtilities.runOnUIThread(watchdog, 5000);
        }

        private boolean stale() {
            return released || job.dead();
        }

        private void append(@Nullable String piece) {
            if (TextUtils.isEmpty(piece)) {
                return;
            }
            piece = piece.trim();
            if (piece.isEmpty()) {
                return;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(piece);
        }

        private void publish(boolean force) {
            final long now = SystemClock.elapsedRealtime();
            // every partial result redraws the bubble: a few times a second is enough
            if (!force && now - lastPublished < 300) {
                return;
            }
            lastPublished = now;
            String shown = text.toString();
            if (!TextUtils.isEmpty(partial)) {
                shown = shown.isEmpty() ? partial : shown + " " + partial;
            }
            if (!shown.isEmpty()) {
                job.callback.onPartial(shown + "…");
            }
        }

        private void nextPhraseOrFinish(long delay) {
            if (!segmented && phrase + 1 < phrases.size()) {
                publish(true);
                final int index = phrase + 1;
                // a short pause: some recognizers report a late error for the session that just ended
                AndroidUtilities.runOnUIThread(() -> {
                    if (!stale()) {
                        startPhrase(index);
                    }
                }, delay);
            } else {
                finished();
            }
        }

        private void finished() {
            final String result = text.toString();
            release();
            complete(job, result);
        }

        private void failed(String error, @Nullable String missingLanguage) {
            release();
            engineFailed(job, error, missingLanguage);
        }

        void release() {
            if (released) {
                return;
            }
            released = true;
            closeSession();
            AndroidUtilities.cancelRunOnUIThread(watchdog);
            if (recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (Throwable ignore) {
                }
                try {
                    recognizer.destroy();
                } catch (Throwable ignore) {
                }
                recognizer = null;
            }
            audio.delete();
        }

        @Nullable
        private static String first(Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            final ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() ? null : list.get(0);
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            touch();
        }

        @Override
        public void onBeginningOfSpeech() {
            touch();
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            lastActivity = SystemClock.elapsedRealtime();
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            touch();
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (stale() || !sessionOpen) {
                return;
            }
            touch();
            partial = first(partialResults);
            publish(false);
        }

        @Override
        public void onSegmentResults(Bundle segmentResults) {
            if (stale() || !sessionOpen) {
                return;
            }
            touch();
            segments++;
            lastSegment = first(segmentResults);
            append(lastSegment);
            partial = null;
            publish(true);
        }

        @Override
        public void onEndOfSegmentedSession() {
            if (stale() || !sessionOpen) {
                return;
            }
            closeSession();
            finished();
        }

        @Override
        public void onResults(Bundle results) {
            if (stale() || !sessionOpen) {
                return;
            }
            closeSession();
            touch();
            final String result = first(results);
            if (segmented && segments == 0 && phrases.size() > 1) {
                // a whole-message session ended at the first pause: this recognizer has no segmented sessions
                segmentedUnsupported = true;
                text.setLength(0);
                startPhrase(0);
                return;
            }
            if (result != null && !result.equals(lastSegment)) {
                append(result);
            }
            partial = null;
            nextPhraseOrFinish(150);
        }

        @Override
        public void onError(int error) {
            if (stale() || !sessionOpen) {
                return;
            }
            closeSession();
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (segmented && segments == 0 && phrases.size() > 1) {
                        // nothing heard at the start of a long message: phrase by phrase, so one noisy bit does not end it
                        text.setLength(0);
                        startPhrase(0);
                        return;
                    }
                    partial = null;
                    nextPhraseOrFinish(150);
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (busyRetries++ < 3) {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (stale()) {
                                return;
                            }
                            if (segmented) {
                                text.setLength(0);
                                segments = 0;
                                startSession(0, audio.samples, true);
                            } else {
                                startPhrase(phrase);
                            }
                        }, 1000);
                        return;
                    }
                    failed(LocaleController.getString(R.string.SG_TranscribeBusy), null);
                    return;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    failed(LocaleController.formatString(R.string.SG_TranscribeLanguage, languageName(language)), onDevice ? language : null);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    failed(LocaleController.getString(R.string.SG_TranscribeMicrophone), null);
                    return;
                case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                    failed(LocaleController.getString(R.string.SG_TranscribeBusy), null);
                    return;
                default:
                    failed(LocaleController.formatString(R.string.SG_TranscribeFailedCode, String.valueOf(error)), null);
            }
        }
    }
    // endregion

    // region The user's AI service

    private static final class AiException extends IOException {
        AiException(String message) {
            super(message);
        }
    }

    private static void startAi(Job job) {
        if (!aiServiceSet()) {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeNoService), null);
            return;
        }
        if (isProtected(job.message)) {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeProtected), null);
            return;
        }
        final File file = job.file;
        if (file.length() > MAX_UPLOAD_BYTES) {
            engineFailed(job, LocaleController.getString(R.string.SG_TranscribeTooLong), null);
            return;
        }
        final TLRPC.Document document = job.message.getDocument();
        final boolean video = job.message.isRoundVideo();
        final String mime = document != null && !TextUtils.isEmpty(document.mime_type) ? document.mime_type : video ? "video/mp4" : "audio/ogg";
        final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        job.stopper = () -> {
            // the socket is closed off the UI thread; the request then ends with an error nobody hears
            final HttpURLConnection c = connection.get();
            if (c != null) {
                new Thread(c::disconnect).start();
            }
        };
        worker.execute(() -> {
            String result = null, error = null;
            if (!job.dead()) {
                try {
                    result = requestAi(file, video, mime, connection);
                } catch (AiException e) {
                    error = e.getMessage();
                } catch (Throwable e) {
                    if (!job.cancelled) {
                        FileLog.e(e);
                    }
                    error = LocaleController.getString(R.string.SG_TranscribeFailed);
                }
            }
            final String text = result, err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (job.dead()) {
                    return;
                }
                if (text != null) {
                    complete(job, text);
                } else {
                    engineFailed(job, err != null ? err : LocaleController.getString(R.string.SG_TranscribeFailed), null);
                }
            });
        });
    }

    /** The model when none is set: Groq names its Whisper differently. */
    public static String defaultAiModel() {
        return SolegramConfig.aiBaseUrl != null && SolegramConfig.aiBaseUrl.contains("groq") ? "whisper-large-v3-turbo" : "whisper-1";
    }

    /** Accepts the base URL (".../v1"), the chat address or the full transcription address. */
    static String transcriptionsUrl(String base) {
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/audio/transcriptions")) {
            return url;
        }
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        return url + "/audio/transcriptions";
    }

    private static String requestAi(File file, boolean video, String mime, AtomicReference<HttpURLConnection> connection) throws Exception {
        final String model = TextUtils.isEmpty(SolegramConfig.transcribeModel) ? defaultAiModel() : SolegramConfig.transcribeModel.trim();
        final String boundary = "SolegramBoundary" + System.nanoTime();
        final String fileName = video ? "video.mp4" : "voice.ogg";
        final byte[] head = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
            + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        final byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        final HttpURLConnection c = (HttpURLConnection) new URL(transcriptionsUrl(SolegramConfig.aiBaseUrl)).openConnection();
        connection.set(c);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(head.length + file.length() + tail.length);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Solegram");
            if (!TextUtils.isEmpty(SolegramConfig.aiApiKey)) {
                c.setRequestProperty("Authorization", "Bearer " + SolegramConfig.aiApiKey.trim());
            }
            try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(file)) {
                out.write(head);
                final byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                out.write(tail);
            }
            final int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                throw new AiException(LocaleController.getString(R.string.SG_TranscribeKeyRejected));
            }
            if (code == 429 || code == 503) {
                throw new AiException(LocaleController.getString(R.string.SG_TranscribeBusy));
            }
            if (code == 404 || code == 405) {
                throw new AiException(LocaleController.getString(R.string.SG_TranscribeNoAudioApi));
            }
            if (code == 413) {
                throw new AiException(LocaleController.getString(R.string.SG_TranscribeTooLong));
            }
            if (code < 200 || code >= 300) {
                throw new AiException(LocaleController.formatString(R.string.SG_TranscribeFailedCode, String.valueOf(code)));
            }
            final String body = read(c.getInputStream()).trim();
            if (body.startsWith("{")) {
                return new JSONObject(body).optString("text", "").trim();
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
    // endregion
}
