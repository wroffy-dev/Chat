/*
 * JNI bridge for RNNoise (docs/08-calling.md, noise reduction). Processes one 10 ms frame of 48 kHz
 * mono audio in place: 480 floats in WebRTC's int16 range, as LiveKit's capture post-processor gets them.
 */
#include <jni.h>
#include <stdint.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL
Java_app_vaultchat_platform_call_RnNoiseProcessor_nativeCreate(JNIEnv *env, jobject self) {
    (void) env; (void) self;
    return (jlong) (intptr_t) rnnoise_create();
}

JNIEXPORT void JNICALL
Java_app_vaultchat_platform_call_RnNoiseProcessor_nativeProcess(JNIEnv *env, jobject self, jlong handle, jobject buffer) {
    (void) self;
    DenoiseState *st = (DenoiseState *) (intptr_t) handle;
    float *samples = (float *) (*env)->GetDirectBufferAddress(env, buffer);
    if (st == NULL || samples == NULL) return;
    if ((*env)->GetDirectBufferCapacity(env, buffer) < (jlong) (FRAME * sizeof(float))) return;
    rnnoise_process_frame(st, samples, samples);
}

JNIEXPORT void JNICALL
Java_app_vaultchat_platform_call_RnNoiseProcessor_nativeDestroy(JNIEnv *env, jobject self, jlong handle) {
    (void) env; (void) self;
    if (handle != 0) rnnoise_destroy((DenoiseState *) (intptr_t) handle);
}
