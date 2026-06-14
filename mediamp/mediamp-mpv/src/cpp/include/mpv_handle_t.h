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
#include <mpv/client.h>
#include <mpv/render_gl.h>

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

#include "compatible_thread.h"
#include "global_lock.h"
#include "log.h"
#include <memory>

namespace mediampv {

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
bool get_property(const char *name, mpv_format format, void *out_result);
bool set_property(const char *name, mpv_format format, void *in_value);
bool observe_property(const char *property, mpv_format format, uint64_t reply_data);
bool unobserve_property(uint64_t reply_data);

bool attach_android_surface(JNIEnv *env, jobject surface);
bool detach_android_surface(JNIEnv *env);

#ifdef __ANDROID__
bool attach_window_surface(int64_t wid);
bool detach_window_surface();
#endif

#if defined(_WIN32) || defined(__linux__)
// Render API (Windows x64 / Linux)
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

std::shared_ptr<mediampv::compatible_thread> event_thread_;
bool event_loop_request_exit = false;

void *event_loop(void *arg);
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
