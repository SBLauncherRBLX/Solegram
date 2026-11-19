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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Free transcription of voice messages and round videos, without Premium: the message's sound is decoded on the
 * phone (MediaExtractor + MediaCodec, to 16-bit mono PCM) and given to Android's speech recognizer as an audio
 * source (Android 13+), the on-device one where the phone has it. The words come back in segments, joined into the
 * text Telegram shows under the message.
 */
public final class SolegramVoiceTranscriber {

    private SolegramVoiceTranscriber() {
    }

    /** Whether this phone can do it at all (Android 13+, a speech recognizer). */
    public static boolean available() {
        if (Build.VERSION.SDK_INT < 33 || !SolegramConfig.freeTranscribe) {
            return false;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) || SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    /** Why the last transcription failed (read on the UI thread when {@code done} got null). */
    public static final int FAIL_NONE = 0, FAIL_NO_RECOGNIZER = 1, FAIL_LANGUAGE = 2, FAIL_NO_WORDS = 3, FAIL_FILE = 4, FAIL_OTHER = 5;
    public static int lastFailure = FAIL_NONE;

    /** The rate the speech recognizers take best (the message's own 48 kHz is resampled to it). */
    private static final int RATE = 16000;

    /** {@code done} gets the text (UI thread), or null when it could not be done ({@link #lastFailure} says why). */
    public static void transcribe(MessageObject message, Utilities.Callback<String> done) {
        lastFailure = FAIL_NONE;
        if (Build.VERSION.SDK_INT < 33 || message == null || message.getDocument() == null) {
            lastFailure = FAIL_NO_RECOGNIZER;
            done.run(null);
            return;
        }
        // its own thread: waiting for the file and decoding take a while, the shared queues are not held
        new Thread(() -> {
            int fail = FAIL_FILE;
            try {
                final File file = waitForFile(message);
                if (file != null) {
                    final Pcm pcm = decode(file);
                    if (pcm != null && pcm.data.length > 0) {
                        final Pcm ready = resample(pcm, RATE);
                        AndroidUtilities.runOnUIThread(() -> recognizeAll(ready, done));
                        return;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                fail = FAIL_OTHER;
            }
            final int f = fail;
            AndroidUtilities.runOnUIThread(() -> {
                lastFailure = f;
                done.run(null);
            });
        }, "sgTranscribe").start();
    }

    /** One way of recognizing: which recognizer, in which language (null: the recognizer's own). */
    private static final class Attempt {
        final boolean onDevice;
        final String language;

        Attempt(boolean onDevice, String language) {
            this.onDevice = onDevice;
            this.language = language;
        }
    }

    /**
     * The ways this phone has, tried one after another until one gives words: the on-device recognizer and the
     * phone's usual one, each in the app's language, then the phone's, then its own default.
     */
    @RequiresApi(33)
    private static void recognizeAll(Pcm pcm, Utilities.Callback<String> done) {
        final Context ctx = ApplicationLoader.applicationContext;
        final ArrayList<String> languages = new ArrayList<>();
        try {
            languages.add(LocaleController.getInstance().getCurrentLocale().toLanguageTag());
        } catch (Throwable ignore) {
        }
        final String system = java.util.Locale.getDefault().toLanguageTag();
        if (!languages.contains(system)) {
            languages.add(system);
        }
        final ArrayList<Attempt> attempts = new ArrayList<>();
        boolean onDevice = false, regular = false;
        try {
            onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        } catch (Throwable ignore) {
        }
        try {
            regular = SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (Throwable ignore) {
        }
        for (String language : languages) {
            if (onDevice) {
                attempts.add(new Attempt(true, language));
            }
            if (regular) {
                attempts.add(new Attempt(false, language));
            }
        }
        if (regular) {
            attempts.add(new Attempt(false, null));
        }
        if (attempts.isEmpty()) {
            lastFailure = FAIL_NO_RECOGNIZER;
            done.run(null);
            return;
        }
        next(pcm, attempts, 0, FAIL_NO_WORDS, done);
    }

    @RequiresApi(33)
    private static void next(Pcm pcm, ArrayList<Attempt> attempts, int index, int fail, Utilities.Callback<String> done) {
        if (index >= attempts.size()) {
            lastFailure = fail;
            done.run(null);
            return;
        }
        final Attempt attempt = attempts.get(index);
        recognize(pcm, attempt, (text, error) -> {
            if (!TextUtils.isEmpty(text)) {
                lastFailure = FAIL_NONE;
                done.run(text);
                return;
            }
            // a missing language beats "no words" as the reason shown, the person can fix it
            final int f = fail == FAIL_LANGUAGE || error == FAIL_LANGUAGE ? FAIL_LANGUAGE : error != FAIL_NONE ? error : fail;
            next(pcm, attempts, index + 1, f, done);
        });
    }

    private interface AttemptDone {
        void run(@Nullable String text, int error);
    }

    /** The message's file: the one on the phone, else asked for and waited for (up to 20 s). */
    @Nullable
    private static File waitForFile(MessageObject message) throws InterruptedException {
        final FileLoader loader = FileLoader.getInstance(message.currentAccount);
        File file = loader.getPathToMessage(message.messageOwner);
        if (file != null && file.exists() && file.length() > 0) {
            return file;
        }
        AndroidUtilities.runOnUIThread(() -> loader.loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0));
        for (int i = 0; i < 100; i++) {
            Thread.sleep(200);
            file = loader.getPathToMessage(message.messageOwner);
            if (file != null && file.exists() && file.length() > 0 && !loader.isLoadingFile(FileLoader.getAttachFileName(message.getDocument()))) {
                return file;
            }
        }
        return null;
    }

    private static final class Pcm {
        byte[] data;
        int sampleRate;
    }

    /** The first audio track of the file as 16-bit mono PCM. */
    @Nullable
    private static Pcm decode(File file) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                return null;
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int idle = 0;
            while (!outputDone && idle < 500) {
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    idle = 0;
                    final ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final ByteBuffer pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
                        if (channels <= 1) {
                            final byte[] chunk = new byte[info.size];
                            pcm.get(chunk);
                            out.write(chunk);
                        } else {
                            // to mono: the channels of each frame averaged
                            final int frames = info.size / (2 * channels);
                            final byte[] chunk = new byte[frames * 2];
                            for (int f = 0; f < frames; f++) {
                                int sum = 0;
                                for (int c = 0; c < channels; c++) {
                                    sum += pcm.getShort((f * channels + c) * 2);
                                }
                                final short v = (short) (sum / channels);
                                chunk[f * 2] = (byte) (v & 0xFF);
                                chunk[f * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                            }
                            out.write(chunk);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = codec.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                } else {
                    idle++;
                }
            }
            final Pcm result = new Pcm();
            result.data = out.toByteArray();
            result.sampleRate = sampleRate;
            return result;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** 16-bit mono PCM to another rate (linear, plenty for speech). */
    private static Pcm resample(Pcm in, int rate) {
        if (in.sampleRate == rate || in.sampleRate <= 0) {
            return in;
        }
        final ByteBuffer src = ByteBuffer.wrap(in.data).order(ByteOrder.LITTLE_ENDIAN);
        final int n = in.data.length / 2;
        final int m = (int) ((long) n * rate / in.sampleRate);
        final ByteBuffer dst = ByteBuffer.allocate(m * 2).order(ByteOrder.LITTLE_ENDIAN);
        final double step = (double) in.sampleRate / rate;
        for (int i = 0; i < m; i++) {
            final double pos = i * step;
            final int a = Math.min(n - 1, (int) pos);
            final int b = Math.min(n - 1, a + 1);
            final double t = pos - a;
            final double v = src.getShort(a * 2) * (1 - t) + src.getShort(b * 2) * t;
            dst.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v))));
        }
        final Pcm out = new Pcm();
        out.data = dst.array();
        out.sampleRate = rate;
        return out;
    }

    /** The PCM through one speech recognizer, in one segmented session that ends with the sound (UI thread). */
    @RequiresApi(33)
    private static void recognize(Pcm pcm, Attempt attempt, AttemptDone done) {
        final Context ctx = ApplicationLoader.applicationContext;
        final ParcelFileDescriptor[] pipe;
        final SpeechRecognizer recognizer;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable e) {
            FileLog.e(e);
            done.run(null, FAIL_OTHER);
            return;
        }
        try {
            recognizer = attempt.onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx);
        } catch (Throwable e) {
            FileLog.e(e);
            closeQuietly(pipe);
            done.run(null, FAIL_NO_RECOGNIZER);
            return;
        }
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (attempt.language != null) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, attempt.language);
        }
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt.onDevice);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate);
        intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);

        final ArrayList<String> parts = new ArrayList<>();
        final boolean[] finished = {false};
        final int[] error = {FAIL_NONE};
        final Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            try {
                recognizer.destroy();
            } catch (Throwable ignore) {
            }
            closeQuietly(pipe);
            done.run(parts.isEmpty() ? null : TextUtils.join(" ", parts).trim(), parts.isEmpty() ? (error[0] != FAIL_NONE ? error[0] : FAIL_NO_WORDS) : FAIL_NONE);
        };
        // a long message still ends: as long as the sound and half a minute more at most
        final long seconds = pcm.data.length / 2L / Math.max(1, pcm.sampleRate);
        AndroidUtilities.runOnUIThread(finish, (seconds + 30) * 1000L);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onError(int code) {
                // "no match" for a quiet part of a long message is not the end of it
                if ((code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !parts.isEmpty()) {
                    return;
                }
                if (code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    error[0] = FAIL_LANGUAGE;
                    if (attempt.onDevice && code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        // the language can be had: the phone fetches it, the next try works offline
                        try {
                            final SpeechRecognizer loader = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
                            final Intent download = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                            download.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            if (attempt.language != null) {
                                download.putExtra(RecognizerIntent.EXTRA_LANGUAGE, attempt.language);
                            }
                            loader.triggerModelDownload(download);
                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    loader.destroy();
                                } catch (Throwable ignore) {
                                }
                            }, 5000);
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                } else if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    error[0] = FAIL_OTHER;
                }
                finish.run();
            }

            @Override
            public void onResults(Bundle results) {
                add(results);
                finish.run();
            }

            @Override
            public void onSegmentResults(Bundle segmentResults) {
                add(segmentResults);
            }

            @Override
            public void onEndOfSegmentedSession() {
                finish.run();
            }

            private void add(Bundle b) {
                final ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && !TextUtils.isEmpty(list.get(0))) {
                    parts.add(list.get(0));
                }
            }
        });
        try {
            recognizer.startListening(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            error[0] = FAIL_OTHER;
            finish.run();
            return;
        }
        // the sound into the pipe, off the UI thread, about 4x faster than it plays; closing it ends the session
        new Thread(() -> {
            try (OutputStream os = new FileOutputStream(pipe[1].getFileDescriptor())) {
                final int chunk = pcm.sampleRate / 10 * 2; // 100 ms
                for (int off = 0; off < pcm.data.length && !finished[0]; off += chunk) {
                    os.write(pcm.data, off, Math.min(chunk, pcm.data.length - off));
                    Thread.sleep(25);
                }
                os.flush();
            } catch (Throwable e) {
                if (!finished[0]) {
                    FileLog.e(e);
                }
            } finally {
                try {
                    pipe[1].close();
                } catch (Throwable ignore) {
                }
            }
        }, "sgTranscribePipe").start();
    }

    private static void closeQuietly(ParcelFileDescriptor[] pipe) {
        for (ParcelFileDescriptor p : pipe) {
            try {
                p.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
