// Copyright (C) 2024-2026 OpenAni and contributors.
//
// Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
//
// https://github.com/open-ani/mediamp/blob/main/LICENSE

//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_MPV_HANDLE_T_H
#define MEDIAMP_MPV_HANDLE_T_H

#include <iostream>
#include <jni.h>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <gl/GL.h>
#endif

#ifdef __linux__
#include <EGL/egl.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#include <dlfcn.h>
#endif

#ifdef __APPLE__
#include <OpenGL/OpenGL.h>
#include <dlfcn.h>
#endif

#include "compatible_thread.h"
#include "global_lock.h"
#include "log.h"

namespace mediampv {

// Forward declarations of mpv types (to avoid requiring mpv/client.h at compile time)
extern "C" {
typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
} mpv_format;

typedef enum mpv_event_id {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_SHUTDOWN = 1,
    MPV_EVENT_LOG_MESSAGE = 2,
    MPV_EVENT_GET_PROPERTY_REPLY = 3,
    MPV_EVENT_SET_PROPERTY_REPLY = 4,
    MPV_EVENT_COMMAND_REPLY = 5,
    MPV_EVENT_START_FILE = 6,
    MPV_EVENT_END_FILE = 7,
    MPV_EVENT_FILE_LOADED = 8,
    MPV_EVENT_PROPERTY_CHANGE = 23,
    MPV_EVENT_QUEUE_OVERFLOW = 24,
} mpv_event_id;

typedef struct mpv_event {
    mpv_event_id event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;

typedef struct mpv_event_property {
    const char *name;
    mpv_format format;
    void *data;
} mpv_event_property;

typedef struct mpv_event_log_message {
    const char *prefix;
    const char *level;
    const char *text;
    const char *log_level;
} mpv_event_log_message;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w, h;
    int internal_format;
} mpv_opengl_fbo;

typedef struct mpv_opengl_init_params {
    void *(*get_proc_address)(void *ctx, const char *name);
    void *get_proc_address_ctx;
} mpv_opengl_init_params;

typedef void (*mpv_render_update_fn)(void *cb_ctx);

enum {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 6,
};

typedef struct mpv_render_param {
    int type;
    void *data;
} mpv_render_param;

enum {
    MPV_END_FILE_REASON_EOF = 0,
    MPV_END_FILE_REASON_STOP = 1,
    MPV_END_FILE_REASON_QUIT = 2,
    MPV_END_FILE_REASON_ERROR = 3,
};

#define MPV_RENDER_API_TYPE_OPENGL "opengl"
}

// Dynamic loader for libmpv
struct MpvDynApi {
    using mpv_create_fn = mpv_handle *(*)();
    using mpv_initialize_fn = int (*)(mpv_handle *);
    using mpv_terminate_destroy_fn = void (*)(mpv_handle *);
    using mpv_set_option_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_set_option_string_fn = int (*)(mpv_handle *, const char *, const char *);
    using mpv_set_property_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_set_property_string_fn = int (*)(mpv_handle *, const char *, const char *);
    using mpv_get_property_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_command_fn = int (*)(mpv_handle *, const char **);
    using mpv_error_string_fn = const char *(*)(int);
    using mpv_free_fn = void (*)(void *);
    using mpv_wait_event_fn = mpv_event *(*)(mpv_handle *, double);
    using mpv_wakeup_fn = void (*)(mpv_handle *);
    using mpv_request_log_messages_fn = int (*)(mpv_handle *, const char *);
    using mpv_observe_property_fn = int (*)(mpv_handle *, uint64_t, const char *, mpv_format);
    using mpv_unobserve_property_fn = int (*)(mpv_handle *, uint64_t);
    using mpv_render_context_create_fn = int (*)(mpv_render_context **, mpv_handle *, mpv_render_param *params[]);
    using mpv_render_context_free_fn = void (*)(mpv_render_context *);
    using mpv_render_context_render_fn = int (*)(mpv_render_context *, mpv_render_param *params[]);
    using mpv_render_context_set_update_callback_fn = void (*)(mpv_render_context *, mpv_render_update_fn, void *);

    void *library = nullptr;
    std::string load_failure;

    mpv_create_fn create_fn = nullptr;
    mpv_initialize_fn initialize_fn = nullptr;
    mpv_terminate_destroy_fn terminate_destroy_fn = nullptr;
    mpv_set_option_fn set_option_fn = nullptr;
    mpv_set_option_string_fn set_option_string_fn = nullptr;
    mpv_set_property_fn set_property_fn = nullptr;
    mpv_set_property_string_fn set_property_string_fn = nullptr;
    mpv_get_property_fn get_property_fn = nullptr;
    mpv_command_fn command_fn = nullptr;
    mpv_error_string_fn error_string_fn = nullptr;
    mpv_free_fn free_fn = nullptr;
    mpv_wait_event_fn wait_event_fn = nullptr;
    mpv_wakeup_fn wakeup_fn = nullptr;
    mpv_request_log_messages_fn request_log_messages_fn = nullptr;
    mpv_observe_property_fn observe_property_fn = nullptr;
    mpv_unobserve_property_fn unobserve_property_fn = nullptr;
    mpv_render_context_create_fn render_context_create_fn = nullptr;
    mpv_render_context_free_fn render_context_free_fn = nullptr;
    mpv_render_context_render_fn render_context_render_fn = nullptr;
    mpv_render_context_set_update_callback_fn render_context_set_update_callback_fn = nullptr;

    void ensure_loaded();
    void do_load();

private:
    template <typename T>
    T load_symbol(const char *name);
};

MpvDynApi &mpv_api();

class mpv_handle_t final {
public:
explicit mpv_handle_t(JNIEnv *env, jobject app_context) {
create(env, app_context);
}
~mpv_handle_t() = default;

void create(JNIEnv *env, jobject app_context);
bool initialize();
bool set_event_listener(JNIEnv *env, jobject listener);
bool destroy(JNIEnv *env);

bool command(const char **args);
bool set_option(const char *key, const char *value);
template<typename T> bool get_property(const char *name, mpv_format format, T *out_result);
template<typename T> bool set_property(const char *name, mpv_format format, T *in_value);
bool observe_property(const char *property, mpv_format format, uint64_t reply_data);
bool unobserve_property(uint64_t reply_data);

bool attach_android_surface(JNIEnv *env, jobject surface);
bool detach_android_surface(JNIEnv *env);

#ifdef __ANDROID__
bool attach_window_surface(int64_t wid);
bool detach_window_surface();
#endif

#if defined(_WIN32) || defined(__linux__)
// Render API (Desktop)
bool create_render_context(uintptr_t device_ptr, uintptr_t context_ptr, uintptr_t drawable_ptr = 0);
bool destroy_render_context();

GLuint create_texture(int width, int height);
bool release_texture();

bool render_frame();
bool debug_render_solid(float red, float green, float blue, float alpha);
std::string read_texture_stats();
#endif

private:
JavaVM *jvm_;
mpv_handle *handle_;

jobject *event_listener_ = nullptr;

#ifdef __ANDROID__
bool surface_attached_ = false;
jobject surface_;
#endif

#if defined(_WIN32) || defined(__linux__)
mpv_render_context *render_context_ = nullptr;
uintptr_t context_ = 0;

GLuint fbo_ = 0, texture_ = 0;
int width_ = 0, height_ = 0;
CREATE_LOCK(texture_lock);
#endif

#ifdef _WIN32
HDC device_ = nullptr;
#endif

#ifdef __linux__
	bool using_egl_ = false;
	EGLDisplay egl_display_ = EGL_NO_DISPLAY;
	EGLSurface egl_pbuffer_surface_ = EGL_NO_SURFACE;
	EGLSurface egl_draw_surface_ = EGL_NO_SURFACE;
	EGLSurface egl_read_surface_ = EGL_NO_SURFACE;
	Display* glx_display_ = nullptr;
	GLXDrawable glx_drawable_ = None;
	bool owns_glx_display_ = false;
	bool owns_glx_drawable_ = false;
	GLXContext glx_context_ = nullptr;
	bool owns_glx_context_ = false;
	bool using_current_ctx_ = false;
#endif

#ifdef __APPLE__
	CGLContextObj cgl_context_ = nullptr;
	CGLPixelFormatObj cgl_pixel_format_ = nullptr;
#endif

std::shared_ptr<mediampv::compatible_thread> event_thread_;
bool event_loop_request_exit = false;

void *event_loop(void *arg);
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
