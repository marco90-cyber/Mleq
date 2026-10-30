// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

#include "mleq_dsp.h"
#include "mleq_loudness.h"

using mleq::Engine;

extern "C" {

JNIEXPORT jlong JNICALL Java_com_marco_mleq_audio_NativeDsp_nCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<Engine*>(h);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nConfigure(JNIEnv*, jobject, jlong h, jint sr, jint ch) {
    reinterpret_cast<Engine*>(h)->configure(sr, ch);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetFilters(JNIEnv* env, jobject, jlong h,
                                                                       jdoubleArray coefs, jint stages,
                                                                       jdouble preGain) {
    if (stages < 0 || env->GetArrayLength(coefs) < stages * 5) return;
    jdouble* p = env->GetDoubleArrayElements(coefs, nullptr);
    reinterpret_cast<Engine*>(h)->setFilters(p, stages, preGain);
    env->ReleaseDoubleArrayElements(coefs, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetLimiter(JNIEnv*, jobject, jlong h, jboolean on,
                                                                       jfloat thr, jfloat rel, jfloat look) {
    reinterpret_cast<Engine*>(h)->setLimiter(on == JNI_TRUE, thr, rel, look);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetCompressor(JNIEnv*, jobject, jlong h, jboolean on,
                                                                          jfloat thr, jfloat ratio, jfloat atk,
                                                                          jfloat rel, jfloat knee, jfloat makeup) {
    reinterpret_cast<Engine*>(h)->setCompressor(on == JNI_TRUE, thr, ratio, atk, rel, knee, makeup);
}

JNIEXPORT jfloat JNICALL Java_com_marco_mleq_audio_NativeDsp_nCompressorGainDb(JNIEnv*, jobject, jlong h, jboolean reset) {
    auto* e = reinterpret_cast<Engine*>(h);
    const float v = e->compressorMinGainDb();
    if (reset == JNI_TRUE) e->resetCompressorMeter();
    return v;
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetStereo(JNIEnv*, jobject, jlong h, jfloat bal,
                                                                       jfloat width) {
    reinterpret_cast<Engine*>(h)->setStereo(bal, width);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nReset(JNIEnv*, jobject, jlong h) {
    reinterpret_cast<Engine*>(h)->reset();
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeDsp_nSetResample(JNIEnv*, jobject, jlong h, jint outRate) {
    reinterpret_cast<Engine*>(h)->setResample(outRate);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nMaxOut(JNIEnv*, jobject, jlong h, jint inFrames) {
    return reinterpret_cast<Engine*>(h)->maxOutFrames(inFrames);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nFlushIn(JNIEnv*, jobject, jlong h) {
    return reinterpret_cast<Engine*>(h)->flushInFrames();
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nLatency(JNIEnv*, jobject, jlong h) {
    return reinterpret_cast<Engine*>(h)->latencyFrames();
}

JNIEXPORT jfloat JNICALL Java_com_marco_mleq_audio_NativeDsp_nLimiterGainDb(JNIEnv*, jobject, jlong h, jboolean reset) {
    auto* e = reinterpret_cast<Engine*>(h);
    const float v = e->limiterMinGainDb();
    if (reset == JNI_TRUE) e->resetMeter();
    return v;
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nProcess(JNIEnv* env, jobject, jlong h, jobject inBuf,
                                                                    jint inOff, jint inEnc, jint frames,
                                                                    jobject outBuf, jint outEnc, jboolean bypass) {
    auto* in = static_cast<uint8_t*>(env->GetDirectBufferAddress(inBuf));
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!in || !out || frames <= 0) return 0;
    return reinterpret_cast<Engine*>(h)->process(in + inOff, inEnc, frames, out, outEnc, bypass == JNI_TRUE);
}

JNIEXPORT jint JNICALL Java_com_marco_mleq_audio_NativeDsp_nFlush(JNIEnv* env, jobject, jlong h, jobject outBuf,
                                                                  jint outEnc, jint maxFrames) {
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuf));
    if (!out) return 0;
    return reinterpret_cast<Engine*>(h)->flush(out, outEnc, maxFrames);
}

JNIEXPORT jlong JNICALL Java_com_marco_mleq_audio_NativeLoudness_nCreate(JNIEnv*, jobject, jint sr, jint ch) {
    return reinterpret_cast<jlong>(new mleq::LoudnessMeter(sr, ch));
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nFeed(JNIEnv* env, jobject, jlong h, jobject buf,
                                                                      jint frames) {
    auto* p = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (p && frames > 0) reinterpret_cast<mleq::LoudnessMeter*>(h)->feed(p, frames);
}

// out[0] = LUFS integrati (-1000 = non misurabile), out[1] = picco dei campioni (lineare)
JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nResult(JNIEnv* env, jobject, jlong h,
                                                                        jdoubleArray out) {
    if (env->GetArrayLength(out) < 2) return;
    auto* m = reinterpret_cast<mleq::LoudnessMeter*>(h);
    jdouble v[2] = {m->integrated(), m->samplePeak()};
    env->SetDoubleArrayRegion(out, 0, 2, v);
}

JNIEXPORT void JNICALL Java_com_marco_mleq_audio_NativeLoudness_nDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<mleq::LoudnessMeter*>(h);
}

}  // extern "C"

