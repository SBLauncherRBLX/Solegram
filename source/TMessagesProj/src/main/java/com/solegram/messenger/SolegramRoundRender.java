/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.graphics.ColorMatrix;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bakes a round video's effects into a finished file before it is sent: every frame goes through the filter's colour
 * matrix and vignette (the same ones the preview shows) on the GPU and is encoded again; the sound goes through the
 * voice effect (or is copied as it was). Telegram then sends the file as it is.
 */
public final class SolegramRoundRender {

    private SolegramRoundRender() {
    }

    private static final int TIMEOUT_US = 10000;

    /** Writes src with the filter and the voice effect into dst; false when it could not be done. */
    public static boolean render(String src, String dst, int filter, int voice, AtomicBoolean cancel) {
        final AudioData audio = voice != SolegramVoiceEffects.NONE ? SolegramRoundFx.encodeAudio(src, voice, cancel) : copyAudio(src);
        if (cancel != null && cancel.get()) {
            return false;
        }
        if (filter == SolegramRoundFx.F_NONE) {
            // only the sound changes: the picture is copied
            return SolegramRoundFx.replaceAudio(src, dst, voice, cancel);
        }
        try {
            return renderVideo(src, dst, filter, audio, cancel);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Encoded sound samples and their format, ready for the muxer. */
    public static final class AudioData {
        public MediaFormat format;
        public final ArrayList<byte[]> data = new ArrayList<>();
        public final ArrayList<long[]> info = new ArrayList<>(); // time, flags
    }

    private static AudioData copyAudio(String src) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(src);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                return null;
            }
            ex.selectTrack(track);
            final AudioData a = new AudioData();
            a.format = ex.getTrackFormat(track);
            final ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024);
            while (true) {
                buffer.clear();
                final int size = ex.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                final byte[] bytes = new byte[size];
                buffer.position(0);
                buffer.get(bytes, 0, size);
                a.data.add(bytes);
                a.info.add(new long[]{ex.getSampleTime(), (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0});
                ex.advance();
            }
            return a;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (ex != null) {
                try { ex.release(); } catch (Throwable ignore) {}
            }
        }
    }

    // region The picture

    private static final String VERTEX = "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "varying vec2 vTex;\n" +
        "varying vec2 vPos;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTex = (uTexMatrix * aTexCoord).xy;\n" +
        "  vPos = aPosition.xy * 0.5 + 0.5;\n" +
        "}\n";

    private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "varying vec2 vPos;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "uniform mat4 uColor;\n" +
        "uniform vec4 uOffset;\n" +
        "uniform float uVignette;\n" +
        "void main() {\n" +
        "  vec4 c = texture2D(sTexture, vTex);\n" +
        "  vec4 o = clamp(uColor * c + uOffset, 0.0, 1.0);\n" +
        "  float d = distance(vPos, vec2(0.5)) / 0.72;\n" +
        "  float v = smoothstep(0.55, 1.0, d) * uVignette;\n" +
        "  gl_FragColor = vec4(mix(o.rgb, vec3(0.0), v), 1.0);\n" +
        "}\n";

    private static boolean renderVideo(String src, String dst, int filter, AudioData audio, AtomicBoolean cancel) throws Exception {
        MediaExtractor ex = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        Surface encoderSurface = null, decoderSurface = null;
        SurfaceTexture texture = null;
        HandlerThread frames = null;
        boolean muxerStarted = false;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(src);
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                return false;
            }
            ex.selectTrack(track);
            final MediaFormat inFormat = ex.getTrackFormat(track);
            final int w = inFormat.getInteger(MediaFormat.KEY_WIDTH) & ~1, h = inFormat.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
            final int rotation = Build.VERSION.SDK_INT >= 23 && inFormat.containsKey(MediaFormat.KEY_ROTATION) ? inFormat.getInteger(MediaFormat.KEY_ROTATION) : 0;
            final int fps = inFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? Math.max(15, Math.min(60, inFormat.getInteger(MediaFormat.KEY_FRAME_RATE))) : 30;

            // the encoder and its surface, with GL drawing into it
            final MediaFormat outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, w * h * 6));
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            final int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return false;
            }
            final int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE
            };
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return false;
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return false;
            }

            final int program = program(VERTEX, FRAGMENT);
            if (program == 0) {
                return false;
            }
            final int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // the decoder into a texture; its frames are signalled on their own thread
            frames = new HandlerThread("SolegramRoundFrames");
            frames.start();
            texture = new SurfaceTexture(tex[0]);
            final Object lock = new Object();
            final boolean[] available = new boolean[1];
            texture.setOnFrameAvailableListener(st -> {
                synchronized (lock) {
                    available[0] = true;
                    lock.notifyAll();
                }
            }, new Handler(frames.getLooper()));
            decoderSurface = new Surface(texture);
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME));
            decoder.configure(inFormat, decoderSurface, null, 0);
            decoder.start();

            // the filter's numbers for the shader
            final float[] color = new float[16], offset = new float[4];
            matrixOf(SolegramRoundFx.previewMatrix(filter), color, offset);
            final float vignette = Math.min(1f, SolegramRoundFx.previewVignette(filter) * 0.9f);
            final FloatBuffer quad = floats(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            final FloatBuffer uv = floats(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
            final int aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            final int aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            final int uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            final int uColor = GLES20.glGetUniformLocation(program, "uColor");
            final int uOffset = GLES20.glGetUniformLocation(program, "uOffset");
            final int uVignette = GLES20.glGetUniformLocation(program, "uVignette");
            final float[] st = new float[16];

            muxer = new MediaMuxer(dst, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != 0) {
                muxer.setOrientationHint(rotation);
            }
            int videoTrack = -1, audioTrack = -1;

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, decodeDone = false, encodeDone = false;
            while (!encodeDone) {
                if (cancel != null && cancel.get()) {
                    return false;
                }
                if (!inputDone) {
                    final int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        final ByteBuffer buffer = decoder.getInputBuffer(in);
                        final int size = buffer == null ? -1 : ex.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                if (!decodeDone) {
                    final int out = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (out >= 0) {
                        final boolean render = info.size > 0;
                        final long pts = info.presentationTimeUs;
                        final boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        decoder.releaseOutputBuffer(out, render);
                        if (render) {
                            boolean got;
                            synchronized (lock) {
                                final long until = System.currentTimeMillis() + 2500;
                                while (!available[0] && System.currentTimeMillis() < until) {
                                    lock.wait(100);
                                }
                                got = available[0];
                                available[0] = false;
                            }
                            if (got) {
                                texture.updateTexImage();
                                texture.getTransformMatrix(st);
                                GLES20.glViewport(0, 0, w, h);
                                GLES20.glUseProgram(program);
                                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
                                GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, st, 0);
                                GLES20.glUniformMatrix4fv(uColor, 1, false, color, 0);
                                GLES20.glUniform4fv(uOffset, 1, offset, 0);
                                GLES20.glUniform1f(uVignette, vignette);
                                GLES20.glEnableVertexAttribArray(aPosition);
                                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, quad);
                                GLES20.glEnableVertexAttribArray(aTexCoord);
                                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, uv);
                                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                                EGLExt.eglPresentationTimeANDROID(display, surface, pts * 1000);
                                EGL14.eglSwapBuffers(display, surface);
                            }
                        }
                        if (eos) {
                            decodeDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }
                // what the encoder has made so far
                while (true) {
                    final int out = encoder.dequeueOutputBuffer(info, decodeDone ? TIMEOUT_US : 0);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audio != null && audio.format != null) {
                            audioTrack = muxer.addTrack(audio.format);
                        }
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (out < 0) {
                        continue;
                    }
                    final ByteBuffer data = encoder.getOutputBuffer(out);
                    if (data != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, data, info);
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encodeDone = true;
                        break;
                    }
                }
            }
            if (!muxerStarted) {
                return false;
            }
            if (audioTrack >= 0) {
                for (int i = 0; i < audio.data.size(); i++) {
                    final byte[] bytes = audio.data.get(i);
                    final long[] meta = audio.info.get(i);
                    info.set(0, bytes.length, meta[0], (int) (meta[1] & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM));
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), info);
                }
            }
            muxer.stop();
            return true;
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Throwable ignore) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Throwable ignore) {}
            try { if (muxer != null) muxer.release(); } catch (Throwable ignore) {}
            try { if (ex != null) ex.release(); } catch (Throwable ignore) {}
            try { if (decoderSurface != null) decoderSurface.release(); } catch (Throwable ignore) {}
            try { if (texture != null) texture.release(); } catch (Throwable ignore) {}
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            try { if (encoderSurface != null) encoderSurface.release(); } catch (Throwable ignore) {}
            if (frames != null) frames.quitSafely();
        }
    }

    /** Android's 4×5 colour matrix (0..255 offsets) as a GL matrix (column-major) and offsets in 0..1. */
    private static void matrixOf(ColorMatrix m, float[] gl, float[] offset) {
        final float[] a = m.getArray();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                gl[col * 4 + row] = a[row * 5 + col];
            }
            offset[row] = a[row * 5 + 4] / 255f;
        }
    }

    private static FloatBuffer floats(float[] v) {
        final FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private static int program(String vertex, String fragment) {
        final int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        final int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        final int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            FileLog.e("SolegramRoundRender link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int shader(int type, String code) {
        final int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, code);
        GLES20.glCompileShader(s);
        final int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            FileLog.e("SolegramRoundRender shader: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    // endregion
}
