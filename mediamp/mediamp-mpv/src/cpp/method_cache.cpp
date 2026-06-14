#define UTIL_EXTERN
#include "method_cache.h"

namespace mediampv {

void jni_cache_classes(JNIEnv *env) {
    if (jni_class_cached) return;

    jni_mediamp_clazz_EventListener = 
            reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/openani/mediamp/mpv/EventListener")));

    // MPV_FORMAT_NONE -> EventListener.onPropertyChange(String)
    jni_mediamp_method_EventListener_onPropertyChange_NONE =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onPropertyChange", "(Ljava/lang/String;)V");
    // MPV_FORMAT_FLAG -> EventListener.onPropertyChange(String, boolean)
    jni_mediamp_method_EventListener_onPropertyChange_FLAG =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onPropertyChange", "(Ljava/lang/String;Z)V");
    // MPV_FORMAT_INT64 -> EventListener.onPropertyChange(String, long)
    jni_mediamp_method_EventListener_onPropertyChange_INT64 =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onPropertyChange", "(Ljava/lang/String;J)V");
    // MPV_FORMAT_DOUBLE -> EventListener.onPropertyChange(String, long)
    jni_mediamp_method_EventListener_onPropertyChange_DOUBLE =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onPropertyChange", "(Ljava/lang/String;D)V");
    // MPV_FORMAT_STRING -> EventListener.onPropertyChange(String, String)
    jni_mediamp_method_EventListener_onPropertyChange_STRING =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onPropertyChange", "(Ljava/lang/String;Ljava/lang/String;)V");
    // MPV_EVENT_END_FILE -> EventListener.onEndFile(int, String)
    jni_mediamp_method_EventListener_onEndFile =
            env->GetMethodID(jni_mediamp_clazz_EventListener, "onEndFile", "(ILjava/lang/String;)V");
#ifdef __ANDROID__
    jni_mediamp_clazz_android_Surface = 
            reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("android/view/Surface")));
#endif
    
    jni_class_cached = true;
}
    
} // namespace mediampv