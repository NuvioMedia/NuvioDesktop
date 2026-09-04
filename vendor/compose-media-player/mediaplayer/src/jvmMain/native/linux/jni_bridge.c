// jni_bridge.c — JNI bridge for Linux NativeVideoPlayer
// Maps Kotlin external methods to the native C API and registers via JNI_OnLoad.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "NativeVideoPlayer.h"

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

static inline VideoPlayer* toCtx(jlong h) {
    return (VideoPlayer*)(uintptr_t)(uint64_t)h;
}

static void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type) {
        (*env)->ThrowNew(env, type, message);
    }
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jlong JNICALL jni_CreatePlayer(JNIEnv* env, jclass cls) {
    VideoPlayer* p = nvp_create();
    return p ? (jlong)(uintptr_t)p : 0L;
}

static void JNICALL jni_OpenUri(JNIEnv* env, jclass cls, jlong handle, jstring uri) {
    if (!handle || !uri) return;
    const char* cUri = (*env)->GetStringUTFChars(env, uri, NULL);
    if (!cUri) return;
    nvp_open_uri(toCtx(handle), cUri);
    (*env)->ReleaseStringUTFChars(env, uri, cUri);
}

static void JNICALL jni_Play(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_play(toCtx(handle));
}

static void JNICALL jni_Pause(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_pause(toCtx(handle));
}

static void JNICALL jni_SetVolume(JNIEnv* env, jclass cls, jlong handle, jfloat volume) {
    if (handle) nvp_set_volume(toCtx(handle), (float)volume);
}

static jfloat JNICALL jni_GetVolume(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_volume(toCtx(handle)) : 0.0f;
}

static jint JNICALL jni_CopyLatestFrame(
    JNIEnv* env,
    jclass cls,
    jlong handle,
    jobject destination,
    jint expected_width,
    jint expected_height,
    jint destination_stride,
    jintArray out_info
) {
    (void)cls;
    if (!handle) {
        throwIllegalArgument(env, "handle must be non-zero");
        return NVP_FRAME_COPY_INVALID;
    }
    if (!destination) {
        throwIllegalArgument(env, "destination must not be null");
        return NVP_FRAME_COPY_INVALID;
    }
    if (!out_info || (*env)->GetArrayLength(env, out_info) < 3) {
        throwIllegalArgument(env, "outInfo must contain at least three integers");
        return NVP_FRAME_COPY_INVALID;
    }
    if (expected_width <= 0 || expected_height <= 0 || destination_stride <= 0) {
        throwIllegalArgument(env, "frame dimensions and stride must be positive");
        return NVP_FRAME_COPY_INVALID;
    }

    jclass destination_class = (*env)->GetObjectClass(env, destination);
    if (!destination_class) return NVP_FRAME_COPY_INVALID;
    jmethodID is_read_only_method =
        (*env)->GetMethodID(env, destination_class, "isReadOnly", "()Z");
    if (!is_read_only_method) {
        (*env)->DeleteLocalRef(env, destination_class);
        return NVP_FRAME_COPY_INVALID;
    }
    const jboolean is_read_only =
        (*env)->CallBooleanMethod(env, destination, is_read_only_method);
    (*env)->DeleteLocalRef(env, destination_class);
    if ((*env)->ExceptionCheck(env)) return NVP_FRAME_COPY_INVALID;
    if (is_read_only == JNI_TRUE) {
        throwIllegalArgument(env, "destination must be writable");
        return NVP_FRAME_COPY_INVALID;
    }

    void* address = (*env)->GetDirectBufferAddress(env, destination);
    const jlong capacity = (*env)->GetDirectBufferCapacity(env, destination);
    if (!address || capacity < 0 || (uint64_t)capacity > (uint64_t)SIZE_MAX) {
        throwIllegalArgument(env, "destination must be a direct ByteBuffer");
        return NVP_FRAME_COPY_INVALID;
    }

    NvpFrameInfo info = {0};
    const int32_t status = nvp_copy_latest_frame(
        toCtx(handle),
        address,
        (size_t)capacity,
        (int32_t)expected_width,
        (int32_t)expected_height,
        (int32_t)destination_stride,
        &info
    );
    const jint metadata[3] = {
        (jint)info.width,
        (jint)info.height,
        (jint)info.source_stride,
    };
    (*env)->SetIntArrayRegion(env, out_info, 0, 3, metadata);
    if ((*env)->ExceptionCheck(env)) return NVP_FRAME_COPY_INVALID;
    return (jint)status;
}

static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass cls, jlong address, jlong size) {
    if (!address || size <= 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, (void*)(uintptr_t)(uint64_t)address, (jlong)size);
}

static jint JNICALL jni_GetFrameWidth(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_frame_width(toCtx(handle)) : 0;
}

static jint JNICALL jni_GetFrameHeight(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_frame_height(toCtx(handle)) : 0;
}

static jint JNICALL jni_SetOutputSize(JNIEnv* env, jclass cls, jlong handle, jint width, jint height) {
    return handle ? (jint)nvp_set_output_size(toCtx(handle), (int32_t)width, (int32_t)height) : 0;
}

static jdouble JNICALL jni_GetVideoDuration(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_duration(toCtx(handle)) : 0.0;
}

static jdouble JNICALL jni_GetCurrentTime(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_current_time(toCtx(handle)) : 0.0;
}

static void JNICALL jni_SeekTo(JNIEnv* env, jclass cls, jlong handle, jdouble time) {
    if (handle) nvp_seek_to(toCtx(handle), (double)time);
}

static void JNICALL jni_DisposePlayer(JNIEnv* env, jclass cls, jlong handle) {
    if (handle) nvp_destroy(toCtx(handle));
}

static void JNICALL jni_SetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle, jfloat speed) {
    if (handle) nvp_set_playback_speed(toCtx(handle), (float)speed);
}

static jfloat JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_playback_speed(toCtx(handle)) : 1.0f;
}

static jstring JNICALL jni_GetVideoTitle(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* s = nvp_get_title(toCtx(handle));
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

static jlong JNICALL jni_GetVideoBitrate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jlong)nvp_get_bitrate(toCtx(handle)) : 0L;
}

static jstring JNICALL jni_GetVideoMimeType(JNIEnv* env, jclass cls, jlong handle) {
    if (!handle) return NULL;
    char* s = nvp_get_mime_type(toCtx(handle));
    if (!s) return NULL;
    jstring result = (*env)->NewStringUTF(env, s);
    free(s);
    return result;
}

static jint JNICALL jni_GetAudioChannels(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_audio_channels(toCtx(handle)) : 0;
}

static jint JNICALL jni_GetAudioSampleRate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jint)nvp_get_audio_sample_rate(toCtx(handle)) : 0;
}

static jfloat JNICALL jni_GetFrameRate(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? nvp_get_frame_rate(toCtx(handle)) : 0.0f;
}

static jboolean JNICALL jni_ConsumeDidPlayToEnd(JNIEnv* env, jclass cls, jlong handle) {
    return handle ? (jboolean)(nvp_consume_did_play_to_end(toCtx(handle)) != 0) : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------

static const JNINativeMethod g_methods[] = {
    { "nCreatePlayer",           "()J",                         (void*)jni_CreatePlayer },
    { "nOpenUri",                "(JLjava/lang/String;)V",      (void*)jni_OpenUri },
    { "nPlay",                   "(J)V",                        (void*)jni_Play },
    { "nPause",                  "(J)V",                        (void*)jni_Pause },
    { "nSetVolume",              "(JF)V",                       (void*)jni_SetVolume },
    { "nGetVolume",              "(J)F",                        (void*)jni_GetVolume },
    { "nCopyLatestFrame",         "(JLjava/nio/ByteBuffer;III[I)I", (void*)jni_CopyLatestFrame },
    { "nWrapPointer",            "(JJ)Ljava/nio/ByteBuffer;",   (void*)jni_WrapPointer },
    { "nGetFrameWidth",          "(J)I",                        (void*)jni_GetFrameWidth },
    { "nGetFrameHeight",         "(J)I",                        (void*)jni_GetFrameHeight },
    { "nSetOutputSize",          "(JII)I",                      (void*)jni_SetOutputSize },
    { "nGetVideoDuration",       "(J)D",                        (void*)jni_GetVideoDuration },
    { "nGetCurrentTime",         "(J)D",                        (void*)jni_GetCurrentTime },
    { "nSeekTo",                 "(JD)V",                       (void*)jni_SeekTo },
    { "nDisposePlayer",          "(J)V",                        (void*)jni_DisposePlayer },
    { "nSetPlaybackSpeed",       "(JF)V",                       (void*)jni_SetPlaybackSpeed },
    { "nGetPlaybackSpeed",       "(J)F",                        (void*)jni_GetPlaybackSpeed },
    { "nGetVideoTitle",          "(J)Ljava/lang/String;",       (void*)jni_GetVideoTitle },
    { "nGetVideoBitrate",        "(J)J",                        (void*)jni_GetVideoBitrate },
    { "nGetVideoMimeType",       "(J)Ljava/lang/String;",       (void*)jni_GetVideoMimeType },
    { "nGetAudioChannels",       "(J)I",                        (void*)jni_GetAudioChannels },
    { "nGetAudioSampleRate",     "(J)I",                        (void*)jni_GetAudioSampleRate },
    { "nGetFrameRate",           "(J)F",                        (void*)jni_GetFrameRate },
    { "nConsumeDidPlayToEnd",    "(J)Z",                        (void*)jni_ConsumeDidPlayToEnd },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = (*env)->FindClass(
        env, "io/github/kdroidfilter/composemediaplayer/linux/LinuxNativeBridge");
    if (!cls) return -1;

    int count = (int)(sizeof(g_methods) / sizeof(g_methods[0]));
    if ((*env)->RegisterNatives(env, cls, g_methods, count) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
