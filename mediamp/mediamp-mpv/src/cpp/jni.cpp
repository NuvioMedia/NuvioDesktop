// Copyright (C) 2024-2026 OpenAni and contributors.
//
// Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
//
// https://github.com/open-ani/mediamp/blob/main/LICENSE

#include <iostream>
#include <jni.h>
#include <cstdio>
#include "mpv_handle_t.h"

#define FN(name) Java_org_openani_mediamp_mpv_MPVHandleKt_##name
#define FN_ANDROID(name) Java_org_openani_mediamp_mpv_MPVHandleAndroid_##name
#define FN_DESKTOP(name) Java_org_openani_mediamp_mpv_MPVHandleDesktop_##name

extern "C" {
JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz);
JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context);
JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr);
JNIEXPORT jboolean JNICALL FN(nSetEventListener)(JNIEnv *env, jclass clazz, jlong ptr, jobject listener);

/**
 * 执行 mpv 命令
 */
JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args);
/**
 * 设置 mpv 选项
 */
JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);

// 设置播放器属性
JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);
JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key);

JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value);
JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value);
JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value);
JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value);

JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring name, jint format, jlong reply_data);
JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data);

// renderer
JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface);
JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr);

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContext)(JNIEnv *env, jclass clazz, jlong ptr, jlong device_ptr, jlong context_ptr, jlong drawable_ptr);
JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContext)(JNIEnv *env, jclass clazz, jlong ptr);
JNIEXPORT jint JNICALL FN_DESKTOP(nCreateTexture)(JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height);
JNIEXPORT jboolean JNICALL FN_DESKTOP(nReleaseTexture)(JNIEnv *env, jclass clazz, jlong ptr);
JNIEXPORT jboolean JNICALL FN_DESKTOP(nRenderFrameToTexture)(JNIEnv *env, jclass clazz, jlong ptr);
JNIEXPORT jboolean JNICALL FN_DESKTOP(nDebugRenderSolid)(JNIEnv *env, jclass clazz, jlong ptr, jfloat red, jfloat green, jfloat blue, jfloat alpha);
JNIEXPORT jstring JNICALL FN_DESKTOP(nReadTextureStats)(JNIEnv *env, jclass clazz, jlong ptr);

/**
 * 关闭此 mpv_handle_t 实例
 */
JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr);
/**
 * 被 GC 调用，回收 mpv_handle_t
 */
JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr);
}

// implementations

JNIEXPORT jboolean JNICALL FN(nGlobalInit)(JNIEnv *env, jclass clazz) {
return true;
}

JNIEXPORT jlong JNICALL FN(nMake)(JNIEnv *env, jclass clazz, jobject app_context) {
auto* handle = new mediampv::mpv_handle_t(env, app_context);
return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL FN(nInitialize)(JNIEnv *env, jclass clazz, jlong ptr) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->initialize();
}

JNIEXPORT jboolean JNICALL FN(nSetEventListener)
(JNIEnv *env, jclass clazz, jlong ptr, jobject listener) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->set_event_listener(env, listener);
}

JNIEXPORT jboolean JNICALL FN(nCommand)(JNIEnv *env, jclass clazz, jlong ptr, jobjectArray args) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *arguments[128] = { 0 };
jsize len = env->GetArrayLength(args);

if (len >= sizeof(arguments) / sizeof(arguments[0])) {
LOG("arguments are too long (>128)");
return false;
}

for (int i = 0; i < len; ++i)
arguments[i] = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(args, i), nullptr);

bool result = instance->command(arguments);

for (int i = 0; i < len; ++i)
env->ReleaseStringUTFChars((jstring)env->GetObjectArrayElement(args, i), arguments[i]);

return result;
}

JNIEXPORT jboolean JNICALL FN(nOption)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *option_key_char = env->GetStringUTFChars(key, nullptr);
const char *option_value_char = env->GetStringUTFChars(value, nullptr);

bool result = instance->set_option(option_key_char, option_value_char);

env->ReleaseStringUTFChars(key, option_key_char);
env->ReleaseStringUTFChars(value, option_value_char);

return result;
}

// property set and get

JNIEXPORT jint JNICALL FN(nGetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);
int64_t result = 0;

instance->get_property(key_char, MPV_FORMAT_INT64, &result);
env->ReleaseStringUTFChars(key, key_char);

return static_cast<jint>(result);
}

JNIEXPORT jdouble JNICALL FN(nGetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);
double result = 0;

instance->get_property(key_char, MPV_FORMAT_DOUBLE, &result);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nGetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);
int result = 0;

instance->get_property(key_char, MPV_FORMAT_FLAG, &result);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jstring JNICALL FN(nGetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);
char *result = nullptr;

instance->get_property(key_char, MPV_FORMAT_STRING, &result);
env->ReleaseStringUTFChars(key, key_char);

jstring jresult = env->NewStringUTF(result ? result : "");
if (result) {
mpv_free(result); // TODO: move to mpv_handle_t
}

return jresult;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyString)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jstring value) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);
const char *value_char = env->GetStringUTFChars(value, nullptr);

bool result = instance->set_property(key_char, MPV_FORMAT_STRING, &value_char);

env->ReleaseStringUTFChars(key, key_char);
env->ReleaseStringUTFChars(value, value_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyInt)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint value) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);

bool result = instance->set_property(key_char, MPV_FORMAT_INT64, &value);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyDouble)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jdouble value) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);

bool result = instance->set_property(key_char, MPV_FORMAT_DOUBLE, &value);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nSetPropertyBoolean)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jboolean value) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);

bool result = instance->set_property(key_char, MPV_FORMAT_FLAG, &value);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nObserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jstring key, jint format, jlong reply_data) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));

const char *key_char = env->GetStringUTFChars(key, nullptr);

bool result = instance->observe_property(key_char, static_cast<mpv_format>(format), reply_data);
env->ReleaseStringUTFChars(key, key_char);

return result;
}

JNIEXPORT jboolean JNICALL FN(nUnobserveProperty)(JNIEnv *env, jclass clazz, jlong ptr, jlong reply_data) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->unobserve_property(reply_data);
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nAttachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr, jobject surface) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->attach_android_surface(env, surface);
}

JNIEXPORT jboolean JNICALL FN_ANDROID(nDetachAndroidSurface)(JNIEnv *env, jclass clazz, jlong ptr) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->detach_android_surface(env);
}

#if defined(_WIN32) || defined(__linux__)

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContext)(JNIEnv * env, jclass clazz, jlong ptr, jlong device_ptr, jlong context_ptr, jlong drawable_ptr) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->create_render_context(
static_cast<uintptr_t>(device_ptr),
static_cast<uintptr_t>(context_ptr),
static_cast<uintptr_t>(drawable_ptr));
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContext)(JNIEnv * env, jclass clazz, jlong ptr) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->destroy_render_context();
}

JNIEXPORT jint JNICALL FN_DESKTOP(nCreateTexture)(JNIEnv * env, jclass clazz, jlong ptr, jint width, jint height) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return (long) instance->create_texture(width, height);
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nReleaseTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->release_texture();
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nRenderFrameToTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->render_frame();
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDebugRenderSolid)(JNIEnv * env, jclass clazz, jlong ptr, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->debug_render_solid(red, green, blue, alpha);
}

JNIEXPORT jstring JNICALL FN_DESKTOP(nReadTextureStats)(JNIEnv * env, jclass clazz, jlong ptr) {
auto *instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
std::string stats = instance->read_texture_stats();
return env->NewStringUTF(stats.c_str());
}

#else

JNIEXPORT jboolean JNICALL FN_DESKTOP(nCreateRenderContext)(JNIEnv * env, jclass clazz, jlong ptr, jlong device_ptr, jlong context_ptr, jlong drawable_ptr) {
return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDestroyRenderContext)(JNIEnv * env, jclass clazz, jlong ptr) {
return JNI_FALSE;
}

JNIEXPORT jint JNICALL FN_DESKTOP(nCreateTexture)(JNIEnv * env, jclass clazz, jlong ptr, jint width, jint height) {
return 0;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nReleaseTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nRenderFrameToTexture)(JNIEnv * env, jclass clazz, jlong ptr) {
return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL FN_DESKTOP(nDebugRenderSolid)(JNIEnv * env, jclass clazz, jlong ptr, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
return JNI_FALSE;
}

JNIEXPORT jstring JNICALL FN_DESKTOP(nReadTextureStats)(JNIEnv * env, jclass clazz, jlong ptr) {
return env->NewStringUTF("unsupported on this platform");
}

#endif

JNIEXPORT jboolean JNICALL FN(nDestroy)(JNIEnv *env, jclass clazz, jlong ptr) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
return instance->destroy(env);
}

JNIEXPORT void JNICALL FN(nFinalize)(JNIEnv *env, jclass clazz, jlong ptr) {
auto* instance = reinterpret_cast<mediampv::mpv_handle_t *>(static_cast<uintptr_t>(ptr));
delete instance;
}
