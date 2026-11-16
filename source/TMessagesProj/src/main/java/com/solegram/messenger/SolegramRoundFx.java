/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.SparseArray;


import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Effects for a round video before it is sent: colour filters (Telegram's own filter pipeline does them when the video
 * is converted) and the voice effects of voice messages on its sound (the sound track is written again, the picture
 * is copied as it is).
 */
public final class SolegramRoundFx {

    private SolegramRoundFx() {
    }

    // region Filters

    public static final int F_NONE = 0, F_VIVID = 1, F_WARM = 2, F_COOL = 3, F_MONO = 4, F_NOIR = 5, F_RETRO = 6, F_DREAM = 7, F_DRAMA = 8;
    public static final int FILTER_COUNT = 9;

    public static int filterName(int f) {
        switch (f) {
            case F_VIVID: return R.string.SG_RoundFilterVivid;
            case F_WARM: return R.string.SG_RoundFilterWarm;
            case F_COOL: return R.string.SG_RoundFilterCool;
            case F_MONO: return R.string.SG_RoundFilterMono;
            case F_NOIR: return R.string.SG_RoundFilterNoir;
            case F_RETRO: return R.string.SG_RoundFilterRetro;
            case F_DREAM: return R.string.SG_RoundFilterDream;
            case F_DRAMA: return R.string.SG_RoundFilterDrama;
            default: return R.string.SG_RoundNone;
        }
    }

    /** The filter as Telegram's photo and video editor would have it set (what the converted video gets). */
    public static MediaController.SavedFilterState filterState(int f) {
        if (f == F_NONE) {
            return null;
        }
        final MediaController.SavedFilterState s = new MediaController.SavedFilterState();
        switch (f) {
            case F_VIVID: s.saturationValue = 35; s.contrastValue = 18; s.enhanceValue = 25; break;
            case F_WARM: s.warmthValue = 45; s.saturationValue = 12; s.exposureValue = 4; break;
            case F_COOL: s.warmthValue = -45; s.saturationValue = 5; break;
            case F_MONO: s.saturationValue = -100; s.contrastValue = 12; break;
            case F_NOIR: s.saturationValue = -100; s.contrastValue = 45; s.exposureValue = -6; s.vignetteValue = 45; break;
            case F_RETRO: s.fadeValue = 35; s.warmthValue = 28; s.saturationValue = -22; s.vignetteValue = 30; s.grainValue = 30; break;
            case F_DREAM: s.fadeValue = 22; s.exposureValue = 14; s.saturationValue = -12; s.contrastValue = -18; s.warmthValue = 10; break;
            case F_DRAMA: s.contrastValue = 42; s.saturationValue = -18; s.exposureValue = -8; s.vignetteValue = 40; break;
        }
        return s;
    }

    /** The same filter for the preview, as a colour matrix (close to what the converted video looks like). */
    public static ColorMatrix previewMatrix(int f) {
        final MediaController.SavedFilterState s = filterState(f);
        final ColorMatrix m = new ColorMatrix();
        if (s == null) {
            return m;
        }
        // saturation
        final ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(Math.max(0f, 1f + s.saturationValue / 100f));
        m.postConcat(sat);
        // contrast round the middle grey (Telegram's: 1 + value * 0.3)
        final float c = 1f + s.contrastValue / 100f * 0.3f * 1.6f;
        final float t = 128f * (1f - c);
        m.postConcat(new ColorMatrix(new float[]{c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0}));
        // warmth: reds up and blues down (or the other way)
        final float w = s.warmthValue / 100f;
        m.postConcat(new ColorMatrix(new float[]{1, 0, 0, 0, 22 * w, 0, 1, 0, 0, 5 * w, 0, 0, 1, 0, -26 * w, 0, 0, 0, 1, 0}));
        // exposure
        final float e = s.exposureValue / 100f * 90f;
        m.postConcat(new ColorMatrix(new float[]{1, 0, 0, 0, e, 0, 1, 0, 0, e, 0, 0, 1, 0, e, 0, 0, 0, 1, 0}));
        // fade: the blacks lifted
        final float fade = s.fadeValue / 100f;
        final float k = 1f - fade * 0.35f;
        m.postConcat(new ColorMatrix(new float[]{k, 0, 0, 0, 255 * fade * 0.3f, 0, k, 0, 0, 255 * fade * 0.3f, 0, 0, k, 0, 255 * fade * 0.3f, 0, 0, 0, 1, 0}));
        return m;
    }

    /** How dark the edge of the preview gets (the converted video has a vignette there). */
    public static float previewVignette(int f) {
        final MediaController.SavedFilterState s = filterState(f);
        return s == null ? 0f : s.vignetteValue / 100f;
    }

    // endregion

    // region Voice effects on the sound

    /**
     * Writes the video again with its sound through a voice effect: the picture is copied sample by sample (the same
     * frames at the same times), the sound is decoded, changed and encoded again (AAC, mono).
     */
    public static boolean replaceAudio(String src, String dst, int effect, AtomicBoolean cancel) {
        final SolegramRoundRender.AudioData sound = encodeAudio(src, effect, cancel);
        if (sound == null || sound.format == null) {
            return false;
        }
        MediaExtractor video = null;
        MediaMuxer muxer = null;
        try {
            video = new MediaExtractor();
            video.setDataSource(src);
            int vTrack = -1;
            for (int i = 0; i < video.getTrackCount(); i++) {
                final String mime = video.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    vTrack = i;
                    break;
                }
            }
            if (vTrack < 0) {
                return false;
            }
            final MediaFormat videoFormat = video.getTrackFormat(vTrack);
            // the picture copied, the new sound next to it
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (Build.VERSION.SDK_INT >= 23 && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
            final int vOut = muxer.addTrack(videoFormat);
            final int aOut = muxer.addTrack(sound.format);
            muxer.start();
            video.selectTrack(vTrack);
            final int max = videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            final ByteBuffer sample = ByteBuffer.allocateDirect(Math.max(max, 1024 * 1024));
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (cancel != null && cancel.get()) {
                    return false;
                }
                sample.clear();
                final int size = video.readSampleData(sample, 0);
                if (size < 0) {
                    break;
                }
                info.set(0, size, video.getSampleTime(), (video.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(vOut, sample, info);
                video.advance();
            }
            for (int i = 0; i < sound.data.size(); i++) {
                final byte[] bytes = sound.data.get(i);
                final long[] meta = sound.info.get(i);
                info.set(0, bytes.length, meta[0], (int) (meta[1] & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                muxer.writeSampleData(aOut, ByteBuffer.wrap(bytes), info);
            }
            muxer.stop();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            try { if (muxer != null) muxer.release(); } catch (Throwable ignore) {}
            try { if (video != null) video.release(); } catch (Throwable ignore) {}
        }
    }

    /**
     * The video's sound through a voice effect, encoded again (AAC, mono), ready for a muxer; null when it could not
     * be done.
     */
    public static SolegramRoundRender.AudioData encodeAudio(String src, int effect, AtomicBoolean cancel) {
        MediaExtractor audio = null;
        MediaCodec decoder = null, encoder = null;
        try {
            audio = new MediaExtractor();
            audio.setDataSource(src);
            int aTrack = -1;
            for (int i = 0; i < audio.getTrackCount(); i++) {
                final String mime = audio.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    aTrack = i;
                    break;
                }
            }
            if (aTrack < 0) {
                return null;
            }

            // the sound, as 16-bit mono samples
            audio.selectTrack(aTrack);
            final MediaFormat audioFormat = audio.getTrackFormat(aTrack);
            int rate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            decoder = MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(audioFormat, null, null, 0);
            decoder.start();
            short[] pcm = new short[rate * 4];
            int pcmLength = 0;
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (cancel != null && cancel.get()) {
                    return null;
                }
                if (!inputDone) {
                    final int in = decoder.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        final ByteBuffer buffer = decoder.getInputBuffer(in);
                        final int size = buffer == null ? -1 : audio.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, audio.getSampleTime(), 0);
                            audio.advance();
                        }
                    }
                }
                final int out = decoder.dequeueOutputBuffer(info, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = decoder.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                } else if (out >= 0) {
                    final ByteBuffer buffer = decoder.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        final java.nio.ShortBuffer shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        final int frames = shorts.remaining() / Math.max(1, channels);
                        if (pcmLength + frames > pcm.length) {
                            pcm = java.util.Arrays.copyOf(pcm, Math.max(pcm.length * 2, pcmLength + frames));
                        }
                        for (int i = 0; i < frames; i++) {
                            int sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += shorts.get(i * channels + c);
                            }
                            pcm[pcmLength++] = (short) (sum / Math.max(1, channels));
                        }
                    }
                    decoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            decoder.stop();
            decoder.release();
            decoder = null;

            short[] processed = SolegramVoiceEffects.apply(java.util.Arrays.copyOf(pcm, pcmLength), effect);
            if (processed.length > pcmLength) {
                processed = java.util.Arrays.copyOf(processed, pcmLength); // as long as the picture
            }

            // the sound encoded again
            final MediaFormat encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            final ArrayList<byte[]> audioData = new ArrayList<>();
            final ArrayList<long[]> audioInfo = new ArrayList<>(); // time, flags
            MediaFormat encodedFormat = null;
            int fed = 0;
            inputDone = false;
            outputDone = false;
            while (!outputDone) {
                if (cancel != null && cancel.get()) {
                    return null;
                }
                if (!inputDone) {
                    final int in = encoder.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        final ByteBuffer buffer = encoder.getInputBuffer(in);
                        if (fed >= processed.length || buffer == null) {
                            encoder.queueInputBuffer(in, 0, 0, fed * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buffer.clear();
                            final int count = Math.min(processed.length - fed, Math.min(2048, buffer.capacity() / 2));
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(processed, fed, count);
                            encoder.queueInputBuffer(in, 0, count * 2, fed * 1000000L / rate, 0);
                            fed += count;
                        }
                    }
                }
                final int out = encoder.dequeueOutputBuffer(info, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encodedFormat = encoder.getOutputFormat();
                } else if (out >= 0) {
                    final ByteBuffer buffer = encoder.getOutputBuffer(out);
                    if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        final byte[] bytes = new byte[info.size];
                        buffer.position(info.offset);
                        buffer.get(bytes, 0, info.size);
                        audioData.add(bytes);
                        audioInfo.add(new long[]{info.presentationTimeUs, info.flags});
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            encoder.stop();
            encoder.release();
            encoder = null;
            if (encodedFormat == null) {
                return null;
            }

            final SolegramRoundRender.AudioData out = new SolegramRoundRender.AudioData();
            out.format = encodedFormat;
            out.data.addAll(audioData);
            out.info.addAll(audioInfo);
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            try { if (decoder != null) decoder.release(); } catch (Throwable ignore) {}
            try { if (encoder != null) encoder.release(); } catch (Throwable ignore) {}
            try { if (audio != null) audio.release(); } catch (Throwable ignore) {}
        }
    }

    // endregion

}
