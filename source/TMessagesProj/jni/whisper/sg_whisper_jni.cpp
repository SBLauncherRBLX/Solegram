// Solegram: the bridge between com.solegram.messenger.SolegramWhisper and whisper.cpp.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include <android/log.h>

#ifndef SG_NO_WHISPER
#include "whisper.h"
#endif

#define SGW_JNI(name) Java_com_solegram_messenger_SolegramWhisper_##name

static std::atomic<bool> g_abort(false);

#ifndef SG_NO_WHISPER
static bool sg_abort_cb(void *) {
    return g_abort.load();
}

static void sg_log(enum ggml_log_level, const char *, void *) {
    // quiet: whisper.cpp prints a lot while loading
}
#endif

extern "C" JNIEXPORT jboolean JNICALL SGW_JNI(nAvailable)(JNIEnv *, jclass) {
#ifdef SG_NO_WHISPER
    return JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL SGW_JNI(nInit)(JNIEnv *env, jclass, jstring jpath) {
#ifdef SG_NO_WHISPER
    return 0;
#else
    if (jpath == nullptr) return 0;
    whisper_log_set(sg_log, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
#endif
}

extern "C" JNIEXPORT void JNICALL SGW_JNI(nFree)(JNIEnv *, jclass, jlong handle) {
#ifndef SG_NO_WHISPER
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
#endif
}

extern "C" JNIEXPORT void JNICALL SGW_JNI(nAbort)(JNIEnv *, jclass) {
    g_abort.store(true);
}

// pcm: 16 kHz mono 16-bit; language: a code ("ru", "en") or "auto". Returns "<lang>\n<text>", or null.
extern "C" JNIEXPORT jstring JNICALL SGW_JNI(nTranscribe)(JNIEnv *env, jclass, jlong handle, jshortArray jpcm, jstring jlang, jint threads) {
#ifdef SG_NO_WHISPER
    return nullptr;
#else
    if (handle == 0 || jpcm == nullptr) return nullptr;
    whisper_context *ctx = reinterpret_cast<whisper_context *>(handle);
    const jsize n = env->GetArrayLength(jpcm);
    if (n <= 0) return nullptr;
    std::vector<float> samples((size_t) n);
    {
        jshort *pcm = env->GetShortArrayElements(jpcm, nullptr);
        if (pcm == nullptr) return nullptr;
        for (jsize i = 0; i < n; i++) samples[(size_t) i] = (float) pcm[i] / 32768.0f;
        env->ReleaseShortArrayElements(jpcm, pcm, JNI_ABORT);
    }
    std::string lang = "auto";
    if (jlang != nullptr) {
        const char *l = env->GetStringUTFChars(jlang, nullptr);
        if (l != nullptr) {
            lang = l;
            env->ReleaseStringUTFChars(jlang, l);
        }
    }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.abort_callback = sg_abort_cb;
    params.abort_callback_user_data = nullptr;
    g_abort.store(false);
    if (whisper_full(ctx, params, samples.data(), (int) samples.size()) != 0) {
        return nullptr;
    }
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t != nullptr) text += t;
    }
    const int lang_id = whisper_full_lang_id(ctx);
    const char *detected = lang_id >= 0 ? whisper_lang_str(lang_id) : nullptr;
    std::string out = std::string(detected != nullptr ? detected : "") + "\n" + text;
    // whisper's text is UTF-8; NewStringUTF wants modified UTF-8, so go through a byte array for safety
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = (jstring) env->NewObject(stringClass, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return result;
#endif
}
