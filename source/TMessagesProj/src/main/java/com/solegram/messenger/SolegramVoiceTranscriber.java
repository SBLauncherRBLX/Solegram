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

    /** {@code done} gets the text (UI thread), or null when it could not be done. */
    public static void transcribe(MessageObject message, Utilities.Callback<String> done) {
        if (Build.VERSION.SDK_INT < 33 || message == null || message.getDocument() == null) {
            done.run(null);
            return;
        }
        // its own thread: waiting for the file and decoding take a while, the shared queues are not held
        new Thread(() -> {
            try {
                final File file = waitForFile(message);
                if (file != null) {
                    final Pcm pcm = decode(file);
                    if (pcm != null && pcm.data.length > 0) {
                        recognize(pcm, done);
                        return;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> done.run(null));
        }, "sgTranscribe").start();
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

    /** The PCM through Android's speech recognizer, in one segmented session that ends with the sound. */
    @RequiresApi(33)
    private static void recognize(Pcm pcm, Utilities.Callback<String> done) throws Exception {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AndroidUtilities.runOnUIThread(() -> {
            final Context ctx = ApplicationLoader.applicationContext;
            final SpeechRecognizer recognizer;
            try {
                recognizer = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    : SpeechRecognizer.createSpeechRecognizer(ctx);
            } catch (Throwable e) {
                FileLog.e(e);
                closeQuietly(pipe);
                done.run(null);
                return;
            }
            final ArrayList<String> parts = new ArrayList<>();
            final boolean[] finished = {false};
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
                done.run(parts.isEmpty() ? null : TextUtils.join(" ", parts).trim());
            };
            // a long message still ends: as long as the sound and a minute more at most
            final long seconds = pcm.data.length / 2L / Math.max(1, pcm.sampleRate);
            AndroidUtilities.runOnUIThread(finish, (seconds + 60) * 1000L);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    // "no match" for a quiet part of a long message is not the end of it
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && !parts.isEmpty()) {
                        return;
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
            final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleController.getInstance().getCurrentLocale().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcm.sampleRate);
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
            try {
                recognizer.startListening(intent);
            } catch (Throwable e) {
                FileLog.e(e);
                finish.run();
                return;
            }
            // the sound into the pipe, off the UI thread; closing it ends the session
            new Thread(() -> {
                try (OutputStream os = new FileOutputStream(pipe[1].getFileDescriptor())) {
                    os.write(pcm.data);
                    os.flush();
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    try {
                        pipe[1].close();
                    } catch (Throwable ignore) {
                    }
                }
            }, "sgTranscribePipe").start();
        });
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
