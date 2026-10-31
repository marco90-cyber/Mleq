// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

#include "dsp_engine.h"
#include "mleq_loudness.h"

using mleq::Engine;

namespace {
inline Engine* E(jlong h) { return reinterpret_cast<Engine*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_marco_mleq_audio_NativeDsp_nCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nDestroy(JNIEnv*, jobject, jlong h) { delete E(h); }

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nConfigure(JNIEnv*, jobject, jlong h, jint sr, jint ch) {
    E(h)->configure(sr, ch);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetParams(JNIEnv* env, jobject, jlong h, jstring text) {
    if (!text) return;
    const char* s = env->GetStringUTFChars(text, nullptr);
    if (!s) return;
    E(h)->setParams(s);
    env->ReleaseStringUTFChars(text, s);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nReset(JNIEnv*, jobject, jlong h) { E(h)->reset(); }

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetResample(JNIEnv*, jobject, jlong h, jint outRate) {
    E(h)->setResample(outRate);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nMaxOut(JNIEnv*, jobject, jlong h, jint inFrames) {
    return E(h)->maxOutFrames(inFrames);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nFlushIn(JNIEnv*, jobject, jlong h) { return E(h)->flushInFrames(); }

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nLatency(JNIEnv*, jobject, jlong h) { return E(h)->latencyFrames(); }

// out: [0] riduzione limiter dB, [1] headroom dB, [2] guadagno d'ingresso dB, [3] stadi attivi
JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nMeters(JNIEnv* env, jobject, jlong h, jdoubleArray out, jboolean reset) {
    if (env->GetArrayLength(out) < 4) return;
    Engine* e = E(h);
    jdouble v[4] = {e->limiterMinGainDb(), e->headroomDb(), e->inputGainDb(), static_cast<jdouble>(e->activeStages())};
    if (reset == JNI_TRUE) e->resetMeters();
    env->SetDoubleArrayRegion(out, 0, 4, v);
}

// Risposta in dB della pila lineare alle frequenze date (per il grafico).
JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nResponse(JNIEnv* env, jobject, jlong h, jdoubleArray freqs,
                                                                     jdoubleArray out) {
    const jsize n = env->GetArrayLength(freqs);
    if (env->GetArrayLength(out) < n) return;
    jdouble* f = env->GetDoubleArrayElements(freqs, nullptr);
    std::vector<jdouble> r(n);
    for (jsize i = 0; i < n; i++) r[i] = E(h)->responseDb(f[i]);
    env->ReleaseDoubleArrayElements(freqs, f, JNI_ABORT);
    env->SetDoubleArrayRegion(out, 0, n, r.data());
}

// Guadagni reali delle 15 bande del grafico (con la "curva fedele" sono ricalcolati).
JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nGraphicApplied(JNIEnv* env, jobject, jlong h, jdoubleArray out) {
    if (env->GetArrayLength(out) < mleq::kGraphicBands) return;
    env->SetDoubleArrayRegion(out, 0, mleq::kGraphicBands, E(h)->graphicApplied());
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nProcess(JNIEnv* env, jobject, jlong h, jobject inBuf,
                                                                    jint inOff, jint inEnc, jint frames,
                                                                    jobject outBuf, jint outEnc, jboolean bypass) {
    auto* in = static_cast<uint8_t*>(env->GetDirectBufferAddress(inBuf));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!in || !out || frames <= 0) return 0;
    return E(h)->process(in + inOff, inEnc, frames, out, outEnc, bypass == JNI_TRUE);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nFlush(JNIEnv* env, jobject, jlong h, jobject outBuf,
                                                                  jint outEnc, jint maxFrames) {
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!out) return 0;
    return E(h)->flush(out, outEnc, maxFrames);
}

// ---- misura di loudness (ReplayGain per i file senza tag)

JNIEXPORT jlong JNICALL Java_com_marco_mleq_audio_NativeLoudness_nCreate(JNIEnv*, jobject, jint sr, jint ch) {
    return reinterpret_cast<jlong>(new mleq::LoudnessMeter(sr, ch));
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nFeed(JNIEnv* env, jobject, jlong h, jobject buf, jint frames) {
    auto* p = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (p && frames > 0) reinterpret_cast<mleq::LoudnessMeter*>(h)->feed(p, frames);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nResult(JNIEnv* env, jobject, jlong h, jdoubleArray out) {
    if (env->GetArrayLength(out) < 2) return;
    auto* m = reinterpret_cast<mleq::LoudnessMeter*>(h);
    jdouble v[2] = {m->integrated(), m->samplePeak()};
    env->SetDoubleArrayRegion(out, 0, 2, v);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<mleq::LoudnessMeter*>(h);
}

}  // extern "C"
