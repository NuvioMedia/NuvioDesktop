#pragma once

#ifndef MEDIAMP_METHOD_CACHE_H
#define MEDIAMP_METHOD_CACHE_H

#include <jni.h>

namespace mediampv {

#ifndef UTIL_EXTERN
#define UTIL_EXTERN extern
#endif
    
UTIL_EXTERN jclass jni_mediamp_clazz_EventListener;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_NONE;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_FLAG;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_INT64;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_DOUBLE;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onPropertyChange_STRING;
UTIL_EXTERN jmethodID jni_mediamp_method_EventListener_onEndFile;
#ifdef __ANDROID__
UTIL_EXTERN jclass jni_mediamp_clazz_android_Surface;
#endif


static bool jni_class_cached = false;
void jni_cache_classes(JNIEnv *env);

// TODO: not thread-safe


} // namespace mediampv

#endif //MEDIAMP_METHOD_CACHE_H
