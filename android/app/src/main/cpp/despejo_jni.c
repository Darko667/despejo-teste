// Ponte JNI mínima entre o app (Whisper.java) e o whisper.cpp.
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

static atomic_int g_progress = 0;

static void on_progress(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user) {
    (void) ctx; (void) state; (void) user;
    atomic_store(&g_progress, progress);
}

JNIEXPORT jlong JNICALL
Java_com_despejo_gravador_Whisper_nInit(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    struct whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_despejo_gravador_Whisper_nFree(JNIEnv *env, jclass cls, jlong ctx) {
    (void) env; (void) cls;
    if (ctx) whisper_free((struct whisper_context *) (intptr_t) ctx);
}

JNIEXPORT jint JNICALL
Java_com_despejo_gravador_Whisper_nProgress(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return atomic_load(&g_progress);
}

JNIEXPORT jbyteArray JNICALL
Java_com_despejo_gravador_Whisper_nTranscribe(JNIEnv *env, jclass cls, jlong ctx_ptr, jfloatArray pcm,
                                              jint n, jint threads, jstring language) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ctx_ptr;
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    jfloat *samples = (*env)->GetFloatArrayElements(env, pcm, NULL);

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = lang;
    wp.translate = false;
    wp.n_threads = threads;
    wp.no_context = true;
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.progress_callback = on_progress;

    atomic_store(&g_progress, 0);
    int rc = whisper_full(ctx, wp, samples, n);

    (*env)->ReleaseFloatArrayElements(env, pcm, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    if (rc != 0) return NULL;

    // Junta os trechos num texto só, separados por espaço.
    int segments = whisper_full_n_segments(ctx);
    size_t total = 0;
    for (int i = 0; i < segments; i++) total += strlen(whisper_full_get_segment_text(ctx, i)) + 1;
    char *out = malloc(total + 1);
    if (!out) return NULL;
    size_t len = 0;
    for (int i = 0; i < segments; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        while (*t == ' ') t++;
        size_t l = strlen(t);
        if (l == 0) continue;
        if (len > 0) out[len++] = ' ';
        memcpy(out + len, t, l);
        len += l;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) out);
    free(out);
    return result;
}
