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

import com.solegram.messenger.ui.SolegramRoundMasks;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Effects for a round video before it is sent: colour filters (Telegram's own filter pipeline does them when the video
 * is converted), the voice effects of voice messages on its sound (the sound track is written again, the picture is
 * copied as it is), and face masks: the face is found in the recorded video once, and a mask follows it frame by
 * frame both in the preview and in the video that is sent.
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
        MediaExtractor video = null, audio = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        try {
            video = new MediaExtractor();
            video.setDataSource(src);
            int vTrack = -1, aTrack = -1;
            for (int i = 0; i < video.getTrackCount(); i++) {
                final String mime = video.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/") && vTrack < 0) {
                    vTrack = i;
                } else if (mime.startsWith("audio/") && aTrack < 0) {
                    aTrack = i;
                }
            }
            if (vTrack < 0 || aTrack < 0) {
                return false;
            }
            final MediaFormat videoFormat = video.getTrackFormat(vTrack);

            // the sound, as 16-bit mono samples
            audio = new MediaExtractor();
            audio.setDataSource(src);
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
                    return false;
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
                    return false;
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
                return false;
            }

            // the picture copied, the new sound next to it
            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (Build.VERSION.SDK_INT >= 23 && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
            final int vOut = muxer.addTrack(videoFormat);
            final int aOut = muxer.addTrack(encodedFormat);
            muxer.start();
            video.selectTrack(vTrack);
            int max = videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            final ByteBuffer sample = ByteBuffer.allocateDirect(Math.max(max, 1024 * 1024));
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
            for (int i = 0; i < audioData.size(); i++) {
                final byte[] bytes = audioData.get(i);
                final long[] meta = audioInfo.get(i);
                info.set(0, bytes.length, meta[0], (int) (meta[1] & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                muxer.writeSampleData(aOut, ByteBuffer.wrap(bytes), info);
            }
            muxer.stop();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            try { if (decoder != null) decoder.release(); } catch (Throwable ignore) {}
            try { if (encoder != null) encoder.release(); } catch (Throwable ignore) {}
            try { if (muxer != null) muxer.release(); } catch (Throwable ignore) {}
            try { if (video != null) video.release(); } catch (Throwable ignore) {}
            try { if (audio != null) audio.release(); } catch (Throwable ignore) {}
        }
    }

    // endregion

    // region Face tracking

    /** Where the face is in a frame: the middle between the eyes, the distance between them, the head's tilt. */
    public static final class Pose {
        /** 0..1 of the frame's width and height */
        public float x, y;
        /** the distance between the eyes, as a part of the frame's width */
        public float eye;
        /** radians, the line of the eyes against the horizontal */
        public float roll;
        /** 0..1: how sure we are the face is there (masks fade with it) */
        public float alpha;

        void set(Pose p) {
            x = p.x; y = p.y; eye = p.eye; roll = p.roll; alpha = p.alpha;
        }
    }

    /** The face through the whole video: a pose about ten times a second, smoothed. */
    public static final class FaceTrack {
        final long[] time;
        final Pose[] poses;
        final int count;

        FaceTrack(ArrayList<Long> times, ArrayList<Pose> list) {
            count = times.size();
            time = new long[count];
            poses = new Pose[count];
            for (int i = 0; i < count; i++) {
                time[i] = times.get(i);
                poses[i] = list.get(i);
            }
        }

        public boolean hasFace() {
            for (int i = 0; i < count; i++) {
                if (poses[i].alpha > 0.5f) {
                    return true;
                }
            }
            return false;
        }

        /** The pose at a time of the video (microseconds), between the two nearest found ones. */
        public void pose(long us, Pose out) {
            out.alpha = 0;
            if (count == 0) {
                return;
            }
            int hi = 0;
            while (hi < count && time[hi] < us) {
                hi++;
            }
            if (hi == 0) {
                out.set(poses[0]);
                return;
            }
            if (hi >= count) {
                out.set(poses[count - 1]);
                return;
            }
            final Pose a = poses[hi - 1], b = poses[hi];
            final float t = (us - time[hi - 1]) / (float) Math.max(1, time[hi] - time[hi - 1]);
            if (a.alpha <= 0 && b.alpha <= 0) {
                return;
            }
            final Pose from = a.alpha > 0 ? a : b, to = b.alpha > 0 ? b : a;
            out.x = from.x + (to.x - from.x) * t;
            out.y = from.y + (to.y - from.y) * t;
            out.eye = from.eye + (to.eye - from.eye) * t;
            float d = to.roll - from.roll;
            out.roll = from.roll + d * t;
            out.alpha = a.alpha + (b.alpha - a.alpha) * t;
        }
    }

    /**
     * Finds the face in every tenth of a second of the video (Google's face detector, else Android's own). Slow
     * (decodes the whole video): call it off the main thread.
     */
    public static FaceTrack trackFaces(Context context, String path, AtomicBoolean cancel) {
        final ArrayList<Long> times = new ArrayList<>();
        final ArrayList<Pose> poses = new ArrayList<>();
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        com.google.android.gms.vision.face.FaceDetector gms = null;
        try {
            try {
                gms = new com.google.android.gms.vision.face.FaceDetector.Builder(context)
                    .setTrackingEnabled(true)
                    .setLandmarkType(com.google.android.gms.vision.face.FaceDetector.ALL_LANDMARKS)
                    .setMode(com.google.android.gms.vision.face.FaceDetector.FAST_MODE)
                    .setProminentFaceOnly(true)
                    .setMinFaceSize(0.15f)
                    .build();
                if (!gms.isOperational()) {
                    gms.release();
                    gms = null;
                }
            } catch (Throwable e) {
                gms = null;
            }
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                return new FaceTrack(times, poses);
            }
            extractor.selectTrack(track);
            final MediaFormat format = extractor.getTrackFormat(track);
            final int rotation = Build.VERSION.SDK_INT >= 23 && format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            final String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            try {
                final MediaFormat flexible = MediaFormat.createVideoFormat(mime, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT));
                for (String key : new String[]{"csd-0", "csd-1", "csd-2"}) {
                    if (format.containsKey(key)) {
                        flexible.setByteBuffer(key, format.getByteBuffer(key));
                    }
                }
                flexible.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                decoder.configure(flexible, null, null, 0);
            } catch (Throwable e) {
                // not every decoder takes the flexible format: its own then (the luma plane is read either way)
                decoder.release();
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(format, null, null, 0);
            }
            decoder.start();
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long lastSample = -1000000;
            byte[] nv21 = null;
            final Pose last = new Pose();
            while (!outputDone) {
                if (cancel != null && cancel.get()) {
                    return null;
                }
                if (!inputDone) {
                    final int in = decoder.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        final ByteBuffer buffer = decoder.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int out = decoder.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    if (info.size > 0 && info.presentationTimeUs - lastSample >= 95000) {
                        Image image = null;
                        try {
                            image = decoder.getOutputImage(out);
                        } catch (Throwable ignore) {
                        }
                        // the luma plane: from the image, else from the raw buffer (it comes first in every YUV layout)
                        ByteBuffer yb = null;
                        int w = 0, h = 0, stride = 0, pixel = 1, left = 0, top = 0;
                        if (image != null) {
                            final Rect crop = image.getCropRect();
                            w = crop.width() & ~1;
                            h = crop.height() & ~1;
                            left = crop.left;
                            top = crop.top;
                            final Image.Plane y = image.getPlanes()[0];
                            yb = y.getBuffer();
                            stride = y.getRowStride();
                            pixel = y.getPixelStride();
                        } else {
                            try {
                                final MediaFormat of = decoder.getOutputFormat();
                                final int fw = of.getInteger(MediaFormat.KEY_WIDTH), fh = of.getInteger(MediaFormat.KEY_HEIGHT);
                                left = of.containsKey("crop-left") ? of.getInteger("crop-left") : 0;
                                top = of.containsKey("crop-top") ? of.getInteger("crop-top") : 0;
                                w = (of.containsKey("crop-right") ? of.getInteger("crop-right") - left + 1 : fw) & ~1;
                                h = (of.containsKey("crop-bottom") ? of.getInteger("crop-bottom") - top + 1 : fh) & ~1;
                                stride = of.containsKey(MediaFormat.KEY_STRIDE) ? of.getInteger(MediaFormat.KEY_STRIDE) : fw;
                                yb = decoder.getOutputBuffer(out);
                                if (yb != null) {
                                    yb = yb.duplicate();
                                    yb.position(info.offset);
                                    yb = yb.slice();
                                }
                            } catch (Throwable ignore) {
                                yb = null;
                            }
                        }
                        if (yb != null && w > 16 && h > 16 && (long) (top + h - 1) * stride + (long) (left + w) * pixel <= yb.capacity()) {
                            lastSample = info.presentationTimeUs;
                            if (nv21 == null || nv21.length != w * h * 3 / 2) {
                                nv21 = new byte[w * h * 3 / 2];
                                java.util.Arrays.fill(nv21, w * h, nv21.length, (byte) 128);
                            }
                            for (int row = 0; row < h; row++) {
                                final int base = (row + top) * stride + left * pixel;
                                if (pixel == 1) {
                                    yb.position(base);
                                    yb.get(nv21, row * w, w);
                                } else {
                                    for (int col = 0; col < w; col++) {
                                        nv21[row * w + col] = yb.get(base + col * pixel);
                                    }
                                }
                            }
                            final Pose pose = detect(gms, nv21, w, h);
                            rotate(pose, rotation);
                            if (pose.alpha > 0) {
                                if (last.alpha > 0) {
                                    // a light smoothing: the found points shake a little from frame to frame
                                    pose.x = last.x + (pose.x - last.x) * 0.7f;
                                    pose.y = last.y + (pose.y - last.y) * 0.7f;
                                    pose.eye = last.eye + (pose.eye - last.eye) * 0.6f;
                                    pose.roll = last.roll + (pose.roll - last.roll) * 0.6f;
                                }
                                last.set(pose);
                            } else if (last.alpha > 0) {
                                // a missed frame keeps the last place, fading
                                pose.set(last);
                                pose.alpha = last.alpha * 0.5f;
                                last.alpha = pose.alpha < 0.2f ? 0 : pose.alpha;
                            }
                            times.add(info.presentationTimeUs);
                            poses.add(pose);
                        }
                        if (image != null) {
                            image.close();
                        }
                    }
                    decoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Throwable ignore) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignore) {}
            try { if (gms != null) gms.release(); } catch (Throwable ignore) {}
        }
        return new FaceTrack(times, poses);
    }

    private static Pose detect(com.google.android.gms.vision.face.FaceDetector gms, byte[] nv21, int w, int h) {
        final Pose pose = new Pose();
        if (gms != null) {
            try {
                final com.google.android.gms.vision.Frame frame = new com.google.android.gms.vision.Frame.Builder()
                    .setImageData(ByteBuffer.wrap(nv21), w, h, ImageFormat.NV21).build();
                final SparseArray<com.google.android.gms.vision.face.Face> faces = gms.detect(frame);
                com.google.android.gms.vision.face.Face best = null;
                for (int i = 0; i < faces.size(); i++) {
                    final com.google.android.gms.vision.face.Face f = faces.valueAt(i);
                    if (best == null || f.getWidth() > best.getWidth()) {
                        best = f;
                    }
                }
                if (best != null) {
                    PointF a = null, b = null;
                    for (com.google.android.gms.vision.face.Landmark l : best.getLandmarks()) {
                        if (l.getType() == com.google.android.gms.vision.face.Landmark.LEFT_EYE) {
                            a = l.getPosition();
                        } else if (l.getType() == com.google.android.gms.vision.face.Landmark.RIGHT_EYE) {
                            b = l.getPosition();
                        }
                    }
                    final PointF p = best.getPosition();
                    if (a == null || b == null) {
                        // the eyes guessed from the face's box
                        a = new PointF(p.x + best.getWidth() * 0.3f, p.y + best.getHeight() * 0.42f);
                        b = new PointF(p.x + best.getWidth() * 0.7f, p.y + best.getHeight() * 0.42f);
                    }
                    if (a.x > b.x) {
                        final PointF t = a;
                        a = b;
                        b = t;
                    }
                    pose.x = (a.x + b.x) / 2f / w;
                    pose.y = (a.y + b.y) / 2f / h;
                    pose.eye = (float) Math.hypot(b.x - a.x, b.y - a.y) / w;
                    pose.roll = (float) Math.atan2(b.y - a.y, b.x - a.x);
                    pose.alpha = 1f;
                    return pose;
                }
                return pose;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        // Android's own detector: the middle of the eyes and their distance, no tilt
        try {
            final int[] pixels = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                final int v = nv21[i] & 0xFF;
                pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            final Bitmap bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565);
            final android.media.FaceDetector.Face[] faces = new android.media.FaceDetector.Face[1];
            final int found = new android.media.FaceDetector(w, h, 1).findFaces(bitmap, faces);
            bitmap.recycle();
            if (found > 0 && faces[0] != null && faces[0].confidence() >= 0.3f) {
                final PointF mid = new PointF();
                faces[0].getMidPoint(mid);
                pose.x = mid.x / w;
                pose.y = mid.y / h;
                pose.eye = faces[0].eyesDistance() / w;
                pose.roll = 0;
                pose.alpha = 1f;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return pose;
    }

    /** From the decoded frame to the frame as it is shown, for a video stored turned. */
    private static void rotate(Pose p, int degrees) {
        if (p.alpha <= 0 || degrees == 0) {
            return;
        }
        final float x = p.x, y = p.y;
        switch ((degrees % 360 + 360) % 360) {
            case 90: p.x = 1 - y; p.y = x; p.roll += (float) (Math.PI / 2); break;
            case 180: p.x = 1 - x; p.y = 1 - y; p.roll += (float) Math.PI; break;
            case 270: p.x = y; p.y = 1 - x; p.roll -= (float) (Math.PI / 2); break;
        }
    }

    // endregion

    // region The mask in the sent video

    /**
     * Draws the mask for each frame of the video while it is converted (Telegram's converter puts this picture over
     * the frame, as it does a drawing): a transparent picture as big as the frame.
     */
    public static final class Overlay {
        private final FaceTrack track;
        private final int mask;
        private final SolegramRoundMasks.Sprites sprites;
        private final Pose pose = new Pose();
        private Bitmap bitmap;
        private Canvas canvas;

        public Overlay(FaceTrack track, int mask, SolegramRoundMasks.Sprites sprites) {
            this.track = track;
            this.mask = mask;
            this.sprites = sprites;
        }

        public Bitmap frame(long timeUs, int w, int h) {
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
            }
            bitmap.eraseColor(0);
            track.pose(timeUs, pose);
            if (pose.alpha > 0.02f) {
                SolegramRoundMasks.draw(canvas, mask, pose, w, h, timeUs / 1000, sprites);
            }
            return bitmap;
        }
    }

    // endregion
}
