// Copyright (C) 2024-2026 OpenAni and contributors.
//
// Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
//
// https://github.com/open-ani/mediamp/blob/main/LICENSE

#include <iostream>
#include <sstream>
#include <vector>
#include "mpv_handle_t.h"
#include "method_cache.h"
#include "compatible_thread.h"
#include "global_lock.h"
#include <mpv/render_gl.h>

#ifdef _WIN32
#include <windows.h>
#include <gl/GL.h>
#endif

#ifdef __linux__
#include <EGL/egl.h>
#include <GL/gl.h>
#include <GL/glx.h>
#include <dlfcn.h>
#include <cstdlib>
#include <clocale>
#endif

extern "C" {
#include <libavcodec/jni.h>
}

#define CHECK_HANDLE() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return false; \
}
#define CHECK_HANDLE_RETURN_INT() if (!handle_) { \
    LOG("mpv handle is not created when %s", __FUNCTION__); \
    return 0; \
}

namespace mediampv {

#if defined(_WIN32) || defined(__linux__)
bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object);
static void* get_proc_address_mpv(void* ctx, const char* name);

#ifndef APIENTRY
#ifdef _WIN32
#define APIENTRY __stdcall
#else
#define APIENTRY
#endif
#endif

#define GL_FRAMEBUFFER            0x8D40
#define GL_COLOR_ATTACHMENT0      0x8CE0
#define GL_RGBA8                  0x8058
#define GL_FRAMEBUFFER_COMPLETE   0x8CD5
typedef void (APIENTRY *PFNGLGENFRAMEBUFFERSPROC)(GLsizei n, GLuint *framebuffers);
typedef void (APIENTRY *PFNGLBINDFRAMEBUFFERPROC)(GLenum target, GLuint framebuffer);
typedef void (APIENTRY *PFNGLFRAMEBUFFERTEXTURE2DPROC)(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
typedef void (APIENTRY *PFNGLDELETEFRAMEBUFFERSPROC)(GLsizei n, const GLuint *framebuffers);
typedef GLenum (APIENTRY *PFNGLCHECKFRAMEBUFFERSTATUSPROC)(GLenum target);

static PFNGLGENFRAMEBUFFERSPROC pfnGlGenFramebuffers = nullptr;
static PFNGLBINDFRAMEBUFFERPROC pfnGlBindFramebuffer = nullptr;
static PFNGLFRAMEBUFFERTEXTURE2DPROC pfnGlFramebufferTexture2D = nullptr;
static PFNGLDELETEFRAMEBUFFERSPROC pfnGlDeleteFramebuffers = nullptr;
static PFNGLCHECKFRAMEBUFFERSTATUSPROC pfnGlCheckFramebufferStatus = nullptr;

static bool gl_functions_loaded = false;
static bool load_gl_functions() {
if (gl_functions_loaded) return true;
pfnGlGenFramebuffers = (PFNGLGENFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glGenFramebuffers");
pfnGlBindFramebuffer = (PFNGLBINDFRAMEBUFFERPROC)get_proc_address_mpv(nullptr, "glBindFramebuffer");
pfnGlFramebufferTexture2D = (PFNGLFRAMEBUFFERTEXTURE2DPROC)get_proc_address_mpv(nullptr, "glFramebufferTexture2D");
pfnGlDeleteFramebuffers = (PFNGLDELETEFRAMEBUFFERSPROC)get_proc_address_mpv(nullptr, "glDeleteFramebuffers");
pfnGlCheckFramebufferStatus = (PFNGLCHECKFRAMEBUFFERSTATUSPROC)get_proc_address_mpv(nullptr, "glCheckFramebufferStatus");
gl_functions_loaded = pfnGlGenFramebuffers && pfnGlBindFramebuffer &&
pfnGlFramebufferTexture2D && pfnGlDeleteFramebuffers &&
pfnGlCheckFramebufferStatus;
return gl_functions_loaded;
}

#endif

CREATE_LOCK(global_guard);
JavaVM *global_jvm = nullptr;

void mpv_handle_t::create(JNIEnv *env, jobject app_context) {
	FP;
	LOCK(global_guard);

	if (!global_jvm) {
		env->GetJavaVM(&global_jvm);
		if (!global_jvm) {
			LOG("failed to get current jvm");
			exit(1); // TODO: don't exit
		}

		av_jni_set_java_vm(global_jvm, &app_context);
	}

	jvm_ = global_jvm;

	// mpv requires LC_NUMERIC=C for correct operation.
	// Non-C locale crashes mpv on some systems (pthread_mutex_lock SIGSEGV).
	// We set it once at first init and leave it; the process-wide effect is
	// acceptable since mpv must remain in C locale for its entire lifetime.
	static bool locale_set = false;
	if (!locale_set) {
		setlocale(LC_NUMERIC, "C");
		locale_set = true;
	}

	handle_ = mpv_create();

// use terminal log level but request verbose messages
// this way --msg-level can be used to adjust later
mpv_request_log_messages(handle_, "terminal-default");
// mpv_set_option_string(handle_, "msg-level", "all=v");
}

bool mpv_handle_t::initialize() {
FP;

if (!handle_) return false;
if (mpv_initialize(handle_) < 0) {
LOG("failed to initialize mpv");
return false;
}

event_thread_ = std::make_shared<mediampv::compatible_thread>([&] {
event_loop(nullptr); });
if (!event_thread_->create()) {
LOG("failed to create event thread");
return false;
}

return true;
}

bool mpv_handle_t::set_event_listener(JNIEnv *env, jobject listener) {
FP;

if (event_listener_ && *event_listener_) {
env->DeleteGlobalRef(*event_listener_);
event_listener_ = nullptr;
}
mediampv::jni_cache_classes(env);

if (env->IsInstanceOf(listener, mediampv::jni_mediamp_clazz_EventListener) != JNI_TRUE) {
LOG("listener is not an instance of EventListener");
return false;
}

if (!event_listener_) event_listener_ = new jobject;
*event_listener_ = env->NewGlobalRef(listener);

return true;
}

bool mpv_handle_t::command(const char **args) {
FP;
CHECK_HANDLE()
return mpv_command(handle_, args) >= 0;
}

bool mpv_handle_t::set_option(const char *key, const char *value) {
FP;
CHECK_HANDLE()
return mpv_set_option_string(handle_, key, value);
}

bool mpv_handle_t::get_property(const char *name, mpv_format format, void *out_result) {
FP;
CHECK_HANDLE()
return mpv_get_property(handle_, name, format, out_result) >= 0;
}

bool mpv_handle_t::set_property(const char *name, mpv_format format, void *in_value) {
FP;
CHECK_HANDLE()
return mpv_set_property(handle_, name, format, in_value) >= 0;
}

bool mpv_handle_t::observe_property(const char *property, mpv_format format, uint64_t reply_data) {
FP;
CHECK_HANDLE()
return mpv_observe_property(handle_, reply_data, property, format) >= 0;
}

bool mpv_handle_t::unobserve_property(uint64_t reply_data) {
FP;
CHECK_HANDLE()
return mpv_unobserve_property(handle_, reply_data) >= 0;
}

CREATE_LOCK(surface_access_lock);

bool mpv_handle_t::attach_android_surface(JNIEnv *env, jobject surface) {
FP;
LOCK(surface_access_lock);
CHECK_HANDLE()

#ifdef __ANDROID__
if (surface_attached_) detach_android_surface(env);
if (env->IsInstanceOf(surface, mediampv::jni_mediamp_clazz_android_Surface) != JNI_TRUE) {
LOG("surface is not instance of android.view.Surface");
return false;
}

jobject ref = env->NewGlobalRef(surface);
int64_t wid = (int64_t)(intptr_t) ref;
surface_ = ref;
surface_attached_ = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;

return surface_attached_;
#else
LOG("attach_android_surface is only implemented on Android");
return false;
#endif
}

bool mpv_handle_t::detach_android_surface(JNIEnv *env) {
FP;
LOCK(surface_access_lock);
CHECK_HANDLE()

#ifdef __ANDROID__
if (!surface_attached_) return false;

int64_t wid = 0;
bool result = mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, (void*) &wid);
env->DeleteGlobalRef(surface_);
surface_attached_ = false;

return result;
#else
LOG("detach_android_surface is only implemented on Android");
return false;
#endif
}

#ifdef __ANDROID__
bool mpv_handle_t::attach_window_surface(int64_t wid) {
	FP;
	CHECK_HANDLE();
	return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}

bool mpv_handle_t::detach_window_surface() {
	FP;
	CHECK_HANDLE();
	int64_t wid = 0;
	return mpv_set_option(handle_, "wid", MPV_FORMAT_INT64, &wid) >= 0;
}
#endif

static void mpv_render_update_callback(void* ctx) {
    mpv_handle* mpv = static_cast<mpv_handle*>(ctx);
    if (mpv) mpv_wakeup(mpv);
}

#if defined(_WIN32) || defined(__linux__)
bool mpv_handle_t::create_render_context(uintptr_t device_ptr, uintptr_t context_ptr, uintptr_t drawable_ptr) {
FP;
CHECK_HANDLE()

if (render_context_)
return true;

context_ = context_ptr;

#ifdef _WIN32
device_ = reinterpret_cast<HDC>(device_ptr);

HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_));

if (!load_gl_functions()) {
LOG("Failed to load OpenGL functions");
wglMakeCurrent(old_dc, old_ctx);
return false;
}

mpv_opengl_init_params gl_init_params{
.get_proc_address = get_proc_address_mpv,
.get_proc_address_ctx = nullptr
};
mpv_render_param params[] = {
{
MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)
},
{
MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params
},
{
MPV_RENDER_PARAM_INVALID, nullptr
},
};

if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
render_context_ = nullptr;
wglMakeCurrent(old_dc, old_ctx);
return false;
}

mpv_render_context_set_update_callback(render_context_, mpv_render_update_callback, static_cast<void*>(handle_));

wglMakeCurrent(old_dc, old_ctx);

#elif defined(__linux__)
	// Use Skiko's X11 Display presence (glDevice) to decide the rendering path.
	// On Wayland with XWayland, WAYLAND_DISPLAY is set but Skiko still provides
	// a valid X11 Display* and GLX context — use the X11/GLX path there.
	// Only take the EGL/Wayland path when no X11 Display is available
	// (native Wayland without XWayland, or Skiko provides 0 for glDevice).
	if (device_ptr != 0) {
		// ---- Try Skiko's EGL context first (texture sharing with Skia) ----
		// On modern Linux with Skiko's OpenGL backend, the EGL context from
		// Skiko is already current on the thread during Canvas rendering.
		// Using it for mpv_render_context_create ensures that FBOs/textures
		// created by mpv live in the same GL context as Skia.
		EGLContext current_egl_ctx = eglGetCurrentContext();
		if (current_egl_ctx != EGL_NO_CONTEXT) {
			EGLDisplay current_egl_dpy = eglGetCurrentDisplay();
			EGLSurface current_egl_draw = eglGetCurrentSurface(EGL_DRAW);
			EGLSurface current_egl_read = eglGetCurrentSurface(EGL_READ);

			if (load_gl_functions()) {
				mpv_opengl_init_params gl_init_params{
					.get_proc_address = get_proc_address_mpv,
					.get_proc_address_ctx = nullptr
				};
				mpv_render_param params[] = {
					{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
					{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
					{MPV_RENDER_PARAM_INVALID, nullptr},
				};

				mpv_render_context* render_ctx = nullptr;
				if (mpv_render_context_create(&render_ctx, handle_, params) >= 0) {
					mpv_render_context_set_update_callback(render_ctx, mpv_render_update_callback, static_cast<void*>(handle_));

					using_egl_ = true;
					egl_display_ = current_egl_dpy;
					egl_pbuffer_surface_ = EGL_NO_SURFACE;
					egl_draw_surface_ = current_egl_draw;
					egl_read_surface_ = current_egl_read;
					context_ = reinterpret_cast<uintptr_t>(current_egl_ctx);
					glx_display_ = nullptr;
					glx_drawable_ = None;
					render_context_ = render_ctx;

					LOG("EGL: using Skiko's context for mpv render");
					return true;
				}
				LOG("EGL: mpv_render_context_create failed with Skiko's context");
			}

			// Restore Skiko's EGL context before falling through
			if (current_egl_dpy != EGL_NO_DISPLAY) {
				eglMakeCurrent(current_egl_dpy, current_egl_draw, current_egl_read, current_egl_ctx);
			}
		}

		// ---- GLX: use current thread context (Skiko's GLX) directly ----
		// During the Canvas callback, Skiko's GLX context is current on the
		// rendering thread.  Using it directly eliminates the need for
		// pbuffers, separate GLX contexts, and texture sharing — all GL
		// objects (FBOs, textures) live in Skiko's own context, so Skia can
		// use them immediately after mpv renders to the FBO.
		{
			GLXContext current_glx = glXGetCurrentContext();
			if (current_glx != nullptr) {
				Display* dpy = reinterpret_cast<Display*>(device_ptr);
				if (!dpy) {
					LOG("GLX: no X11 display for current context");
					return false;
				}
				LOG("GLX: using current thread GLX context directly");
				using_egl_ = false;
				egl_display_ = EGL_NO_DISPLAY;
				egl_pbuffer_surface_ = EGL_NO_SURFACE;
				glx_display_ = dpy;
				glx_context_ = current_glx;
				glx_drawable_ = glXGetCurrentDrawable();
				owns_glx_display_ = false;
				owns_glx_context_ = false;
				owns_glx_drawable_ = false;
				using_current_ctx_ = true;

				if (!load_gl_functions()) {
					LOG("GLX: load_gl_functions failed with current context");
					return false;
				}

				mpv_opengl_init_params gl_init_params{
					.get_proc_address = get_proc_address_mpv,
					.get_proc_address_ctx = nullptr
				};
				mpv_render_param params[] = {
					{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
					{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
					{MPV_RENDER_PARAM_INVALID, nullptr},
				};

				if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
					render_context_ = nullptr;
					LOG("GLX: mpv_render_context_create failed with current context");
					return false;
				}

				mpv_render_context_set_update_callback(render_context_, mpv_render_update_callback, static_cast<void*>(handle_));
				LOG("GLX: mpv_render_context_create succeeded with current context");
				return true;
			}
			LOG("GLX: no current context, checking window drawable …");
		}

		// ---- GLX: try Skiko's context with native window drawable ----
		// When no GLX context is current on this thread (which commonly
		// happens on some Linux/Skiko combinations), attempt to use the
		// X11 Window handle provided by OpenGLComponentProvider.glDrawable
		// to make Skiko's own GLX context current.  This avoids creating a
		// pbuffer and a separate GLX context, and more importantly ensures
		// that all GL objects (textures, FBOs) are created in Skiko's
		// context namespace, making them directly accessible to Skia.
		if (drawable_ptr != 0) {
			Display* dpy = reinterpret_cast<Display*>(device_ptr);
			GLXContext skiko_ctx = reinterpret_cast<GLXContext>(context_ptr);
			Window window = static_cast<Window>(drawable_ptr);
			LOG("GLX: attempting glXMakeCurrent with Skiko window=%lu", (unsigned long)window);
			if (glXMakeCurrent(dpy, window, skiko_ctx)) {
				LOG("GLX: using Skiko's context with native window");
				using_egl_ = false;
				egl_display_ = EGL_NO_DISPLAY;
				egl_pbuffer_surface_ = EGL_NO_SURFACE;
				glx_display_ = dpy;
				glx_context_ = skiko_ctx;
				glx_drawable_ = window;
				owns_glx_display_ = false;
				owns_glx_context_ = false;
				owns_glx_drawable_ = false;
				using_current_ctx_ = true;

				if (!load_gl_functions()) {
					LOG("GLX: load_gl_functions failed with window drawable");
					glXMakeCurrent(dpy, None, None);
					return false;
				}

				mpv_opengl_init_params gl_init_params{
					.get_proc_address = get_proc_address_mpv,
					.get_proc_address_ctx = nullptr
				};
				mpv_render_param params[] = {
					{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
					{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
					{MPV_RENDER_PARAM_INVALID, nullptr},
				};

				if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
					render_context_ = nullptr;
					LOG("GLX: mpv_render_context_create failed with window drawable");
					glXMakeCurrent(dpy, None, None);
					return false;
				}

				mpv_render_context_set_update_callback(render_context_, mpv_render_update_callback, static_cast<void*>(handle_));
				LOG("GLX: mpv_render_context_create succeeded with window drawable");
				return true;
			}
			LOG("GLX: glXMakeCurrent with window failed, falling back to pbuffer");
		} else {
			LOG("GLX: no window drawable (drawable_ptr=0), falling back to pbuffer");
		}

		// ---- X11 / XWayland: Try EGL first (hwdec interop), fall back to GLX ----
		// We prefer EGL because hardware-accelerated video decoding (VA-API
		// on Intel/AMD, NVDEC on NVIDIA) needs EGL for zero-copy interop with
		// OpenGL.  GLX can only do copy-back decoding (vaapi-copy, nvdec-copy).
		//
		// NVIDIA's EGL driver has a known bug on Wayland: calling EGL functions
		// when WAYLAND_DISPLAY is set and libnvidia-egl-wayland.so is loaded
		// crashes with SIGSEGV in pthread_mutex_lock.  We dlopen the library
		// with RTLD_NOLOAD to check if it is already present; if so, we skip EGL.
		{
			// Check if any NVIDIA EGL-Wayland library is already loaded.
			// On this system the soname is libnvidia-egl-wayland.so.1 or
			// libnvidia-egl-wayland2.so; try both.
			bool nvidia_wl_loaded = false;
			void* nvidia_wl = dlopen("libnvidia-egl-wayland.so.1", RTLD_LAZY | RTLD_NOLOAD);
			if (nvidia_wl) { nvidia_wl_loaded = true; dlclose(nvidia_wl); }
			if (!nvidia_wl_loaded) {
				nvidia_wl = dlopen("libnvidia-egl-wayland2.so", RTLD_LAZY | RTLD_NOLOAD);
				if (nvidia_wl) { nvidia_wl_loaded = true; dlclose(nvidia_wl); }
			}
			if (!nvidia_wl_loaded) {
				nvidia_wl = dlopen("libnvidia-egl-wayland.so", RTLD_LAZY | RTLD_NOLOAD);
				if (nvidia_wl) { nvidia_wl_loaded = true; dlclose(nvidia_wl); }
			}

			if (!nvidia_wl_loaded) {
				Display* egl_dpy = reinterpret_cast<Display*>(device_ptr);
				if (egl_dpy) {
					// Save old GLX state so we can restore after EGL attempt
					Display* old_dpy = glXGetCurrentDisplay();
					GLXDrawable old_drawable = glXGetCurrentDrawable();
					GLXContext old_ctx = glXGetCurrentContext();

					// Temporarily unset WAYLAND_DISPLAY to force X11 EGL platform.
					const char* saved_wayland = getenv("WAYLAND_DISPLAY");
					bool had_wayland = saved_wayland && saved_wayland[0];
					if (had_wayland) unsetenv("WAYLAND_DISPLAY");

					EGLDisplay egl_display = EGL_NO_DISPLAY;

					// Use eglGetPlatformDisplay with explicit X11 platform (EGL 1.5)
					// to completely bypass Wayland auto-detection.
					typedef EGLDisplay (EGLAPIENTRYP PFNEGLGETPLATFORMDISPLAYPROC)(EGLenum, void*, const EGLAttrib*);
#ifndef EGL_PLATFORM_X11_KHR
#define EGL_PLATFORM_X11_KHR 0x31D5
#endif
					PFNEGLGETPLATFORMDISPLAYPROC pf_eglGetPlatformDisplay =
						(PFNEGLGETPLATFORMDISPLAYPROC)eglGetProcAddress("eglGetPlatformDisplay");
					if (pf_eglGetPlatformDisplay) {
						egl_display = pf_eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, (void*)egl_dpy, nullptr);
					}
					if (egl_display == EGL_NO_DISPLAY) {
						egl_display = eglGetDisplay((EGLNativeDisplayType)egl_dpy);
					}

					if (egl_display != EGL_NO_DISPLAY) {
						EGLint major, minor;
						if (eglInitialize(egl_display, &major, &minor)) {
							EGLint config_attribs[] = {
								EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
								EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
								EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
								EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
								EGL_DEPTH_SIZE, 24, EGL_NONE
							};
							EGLint config_count;
							EGLConfig config;
							if (eglChooseConfig(egl_display, config_attribs, &config, 1, &config_count) && config_count > 0) {
								EGLint pb_attribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
								EGLSurface pbuffer = eglCreatePbufferSurface(egl_display, config, pb_attribs);
								if (pbuffer != EGL_NO_SURFACE) {
									EGLContext egl_ctx = eglCreateContext(egl_display, config, EGL_NO_CONTEXT, nullptr);
									if (egl_ctx != EGL_NO_CONTEXT) {
										if (eglMakeCurrent(egl_display, pbuffer, pbuffer, egl_ctx)) {
											if (load_gl_functions()) {
												mpv_opengl_init_params gl_init_params{
													.get_proc_address = get_proc_address_mpv,
													.get_proc_address_ctx = nullptr
												};
												mpv_render_param params[] = {
													{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
													{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
													{MPV_RENDER_PARAM_INVALID, nullptr},
												};

												mpv_render_context* render_ctx = nullptr;
												if (mpv_render_context_create(&render_ctx, handle_, params) >= 0) {
													mpv_render_context_set_update_callback(render_ctx, mpv_render_update_callback, static_cast<void*>(handle_));

													using_egl_ = true;
													egl_display_ = egl_display;
													egl_pbuffer_surface_ = pbuffer;
													context_ = reinterpret_cast<uintptr_t>(egl_ctx);
													glx_display_ = nullptr;
													glx_drawable_ = None;
													render_context_ = render_ctx;

													if (had_wayland) setenv("WAYLAND_DISPLAY", saved_wayland, 1);
													if (old_ctx) glXMakeCurrent(old_dpy, old_drawable, old_ctx);

													LOG("EGL: render context created (v%d.%d)", major, minor);
													return true;
												}
											}
											eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
										}
										eglDestroyContext(egl_display, egl_ctx);
									}
									eglDestroySurface(egl_display, pbuffer);
								}
							}
							eglTerminate(egl_display);
						}
					}

					if (had_wayland) setenv("WAYLAND_DISPLAY", saved_wayland, 1);
					if (old_ctx) glXMakeCurrent(old_dpy, old_drawable, old_ctx);
				}
			}
		}

		// ---- GLX fallback path (no current context — create pbuffer) ----
		glx_path:
		using_egl_ = false;
		egl_display_ = EGL_NO_DISPLAY;
		egl_pbuffer_surface_ = EGL_NO_SURFACE;

		Display* dpy = reinterpret_cast<Display*>(device_ptr);
		if (!dpy) {
			LOG("GLX: no X11 display (device_ptr=0)");
			return false;
		}

		glx_display_ = dpy;
		owns_glx_display_ = false;
		auto glx_ctx = reinterpret_cast<GLXContext>(context_ptr);
		glx_context_ = glx_ctx;
		owns_glx_context_ = false;

		LOG("GLX: creating pbuffer on Skiko display=%p ctx=%p", (void*)dpy, (void*)glx_ctx);
		int fbAttribs[] = {GLX_RENDER_TYPE, GLX_RGBA_BIT, GLX_DRAWABLE_TYPE, GLX_PBUFFER_BIT, GLX_DOUBLEBUFFER, False, None};
		int fbCount;
		GLXFBConfig* fbc = glXChooseFBConfig(dpy, DefaultScreen(dpy), fbAttribs, &fbCount);
		if (!fbc || fbCount == 0) {
			LOG("GLX: no suitable pbuffer config");
			return false;
		}

		int pbAttribs[] = {GLX_PBUFFER_WIDTH, 1, GLX_PBUFFER_HEIGHT, 1, None};
		GLXDrawable drawable = glXCreatePbuffer(dpy, fbc[0], pbAttribs);
		GLXFBConfig fbconfig = fbc[0];
		XFree(fbc);

		if (drawable == None) {
			LOG("GLX: pbuffer creation failed");
			return false;
		}

		glx_drawable_ = drawable;
		owns_glx_drawable_ = true;
		using_current_ctx_ = false;

		Display* old_dpy = glXGetCurrentDisplay();
		GLXDrawable old_drawable = glXGetCurrentDrawable();
		GLXContext old_glx_ctx = glXGetCurrentContext();

		LOG("GLX: glXMakeCurrent(dpy=%p, drawable=%lu, ctx=%p)", (void*)dpy, (unsigned long)drawable, (void*)glx_ctx);
		if (!glXMakeCurrent(dpy, drawable, glx_ctx)) {
			LOG("GLX: glXMakeCurrent failed with Skiko's context, trying our own");
			GLXContext our_ctx = glXCreateNewContext(dpy, fbconfig, GLX_RGBA_TYPE, glx_ctx, True);
			if (!our_ctx) {
				LOG("GLX: shared context creation failed, trying without sharing");
				our_ctx = glXCreateNewContext(dpy, fbconfig, GLX_RGBA_TYPE, None, True);
			}
			if (!our_ctx || !glXMakeCurrent(dpy, drawable, our_ctx)) {
				LOG("GLX: own context also failed");
				if (our_ctx) glXDestroyContext(dpy, our_ctx);
				glXDestroyPbuffer(dpy, drawable);
				glx_drawable_ = None;
				owns_glx_drawable_ = false;
				if (old_dpy && old_glx_ctx) glXMakeCurrent(old_dpy, old_drawable, old_glx_ctx);
				return false;
			}
			LOG("GLX: glXMakeCurrent succeeded with our own context");
			glx_ctx = our_ctx;
			glx_context_ = our_ctx;
			owns_glx_context_ = true;
		}
		LOG("GLX: glXMakeCurrent succeeded");

		if (!load_gl_functions()) {
			LOG("Failed to load OpenGL functions");
			// Restore Skiko's context before cleanup
			if (old_dpy && old_glx_ctx) glXMakeCurrent(old_dpy, old_drawable, old_glx_ctx);
			if (owns_glx_context_ && glx_context_) glXDestroyContext(dpy, glx_context_);
			glx_context_ = nullptr;
			owns_glx_context_ = false;
			glXDestroyPbuffer(dpy, drawable);
			glx_drawable_ = None;
			owns_glx_drawable_ = false;
			return false;
		}

		mpv_opengl_init_params gl_init_params{
			.get_proc_address = get_proc_address_mpv,
			.get_proc_address_ctx = nullptr
		};
		mpv_render_param params[] = {
			{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
			{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
			{MPV_RENDER_PARAM_INVALID, nullptr},
		};

		if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
			render_context_ = nullptr;
			LOG("GLX: mpv_render_context_create failed");
			// Restore Skiko's context before cleanup
			if (old_dpy && old_glx_ctx) glXMakeCurrent(old_dpy, old_drawable, old_glx_ctx);
			if (owns_glx_context_ && glx_context_) glXDestroyContext(dpy, glx_context_);
			glx_context_ = nullptr;
			owns_glx_context_ = false;
			glXDestroyPbuffer(dpy, drawable);
			glx_drawable_ = None;
			owns_glx_drawable_ = false;
			return false;
		}

		mpv_render_context_set_update_callback(render_context_, mpv_render_update_callback, static_cast<void*>(handle_));
		LOG("GLX: mpv_render_context_create succeeded");

		// Restore Skiko's original drawable/context so Skiko can continue rendering
		if (old_dpy && old_glx_ctx) {
			glXMakeCurrent(old_dpy, old_drawable, old_glx_ctx);
		}

	} else {
		// ---- EGL path (device_ptr == 0) ----
		// Used for both:
		//   - Native Wayland (device_ptr == 0): context must be current to
		//     avoid NVIDIA EGL-Wayland crash.
		//   - X11 / XWayland when Skiko uses EGL (device_ptr != 0, context_ptr
		//     is EGLContext, not GLXContext): use handles from Kotlin directly.
		using_egl_ = true;
		glx_display_ = nullptr;
		glx_drawable_ = None;
		egl_display_ = EGL_NO_DISPLAY;
		egl_pbuffer_surface_ = EGL_NO_SURFACE;

		EGLDisplay old_egl_display = EGL_NO_DISPLAY;
		EGLContext old_egl_ctx = EGL_NO_CONTEXT;
		EGLSurface old_egl_draw = EGL_NO_SURFACE;
		EGLSurface old_egl_read = EGL_NO_SURFACE;
		EGLContext egl_ctx = EGL_NO_CONTEXT;
		bool got_context_from_current = false;

		// ---- Step 1: Obtain EGLDisplay and EGLContext ----
		EGLContext current_ctx = eglGetCurrentContext();
		if (current_ctx != EGL_NO_CONTEXT) {
			// Context is current on this thread (e.g. inside Canvas callback).
			// Save Skiko's state so we can restore it later.
			LOG("EGL: context current, proceeding …");
			got_context_from_current = true;
			old_egl_display = eglGetCurrentDisplay();
			old_egl_ctx = eglGetCurrentContext();
			old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
			old_egl_read = eglGetCurrentSurface(EGL_READ);

			egl_display_ = old_egl_display;
			egl_ctx = current_ctx;
			context_ = reinterpret_cast<uintptr_t>(current_ctx);
			egl_draw_surface_ = old_egl_draw;
			egl_read_surface_ = old_egl_read;
		} else if (device_ptr != 0) {
			// Should not reach here — the GLX path above tried to create its
			// own GLX context and failed.  Creating our own EGL context on
			// NVIDIA+Wayland crashes the EGL driver (SIGSEGV in
			// pthread_mutex_lock), so bail.
			LOG("EGL: no context current, device_ptr available, but GLX already failed — aborting");
			return false;
		} else {
			// On Wayland with NVIDIA's proprietary driver, calling any EGL
			// function from a thread where Skiko has no current context (e.g.,
			// DisposableEffect, before the first Canvas frame) crashes with a
			// SIGSEGV in pthread_mutex_lock deep inside libnvidia-egl-wayland.
			// Bail early — the Canvas callback will retry with a current context.
			LOG("EGL: no context current and no display handle — will retry later");
			return false;
		}

		// Helper to restore/destroy EGL state after init (success or failure).
		// Must be called AFTER mpv_render_context_create attempt.
		auto egl_finalize = [&]() {
			if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
				// Restore Skiko's EGL context (saved at entry)
				eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
			} else if (egl_ctx != EGL_NO_CONTEXT && !got_context_from_current) {
				// We created our own context — unbind it to avoid interfering with Skiko.
				eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			}
			if (egl_pbuffer_surface_ != EGL_NO_SURFACE) {
				eglDestroySurface(egl_display_, egl_pbuffer_surface_);
				egl_pbuffer_surface_ = EGL_NO_SURFACE;
			}
			if (!got_context_from_current && egl_ctx != EGL_NO_CONTEXT) {
				eglDestroyContext(egl_display_, egl_ctx);
				egl_ctx = EGL_NO_CONTEXT;
			}
		};

		LOG("EGL: loading OpenGL functions …");
		if (!load_gl_functions()) {
			LOG("EGL: failed to load OpenGL functions");
			egl_finalize();
			return false;
		}
		{
			mpv_opengl_init_params gl_init_params{
				.get_proc_address = get_proc_address_mpv,
				.get_proc_address_ctx = nullptr
			};
			mpv_render_param params[] = {
				{MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
				{MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init_params},
				{MPV_RENDER_PARAM_INVALID, nullptr},
			};

			if (mpv_render_context_create(&render_context_, handle_, params) < 0) {
				render_context_ = nullptr;
				LOG("EGL: mpv_render_context_create failed");
				egl_finalize();
				return false;
			}
		}
		mpv_render_context_set_update_callback(render_context_, mpv_render_update_callback, static_cast<void*>(handle_));
		LOG("EGL: mpv_render_context_create succeeded");
		egl_finalize();
	}

#endif

return true;
}

#ifdef _WIN32
static void* get_proc_address_mpv(void* ctx, const char* name) {
void* addr = (void*)wglGetProcAddress(name);
if (addr == nullptr || (reinterpret_cast<intptr_t>(addr) >= -1 && reinterpret_cast<intptr_t>(addr) <= 3)) {
static HMODULE opengl32 = LoadLibraryA("opengl32.dll");
if (opengl32) {
addr = (void*)GetProcAddress(opengl32, name);
}
}
return addr;
}
#elif defined(__linux__)
static void* get_proc_address_mpv(void* ctx, const char* name) {
void* addr = (void*)eglGetProcAddress(name);
if (!addr) {
addr = (void*)glXGetProcAddress((const GLubyte*)name);
}
if (!addr) {
addr = dlsym(RTLD_DEFAULT, name);
}
return addr;
}
#endif

bool mpv_handle_t::destroy_render_context() {
FP;
CHECK_HANDLE()

if (!render_context_)
return false;

#ifdef _WIN32
	HDC old_dc = wglGetCurrentDC();
	HGLRC old_ctx = wglGetCurrentContext();
	wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_));
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (old_egl_ctx != reinterpret_cast<EGLContext>(context_)) {
			EGLSurface draw_surf = egl_draw_surface_ != EGL_NO_SURFACE ? egl_draw_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_draw);
			EGLSurface read_surf = egl_read_surface_ != EGL_NO_SURFACE ? egl_read_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_read);
			eglMakeCurrent(egl_display_, draw_surf, read_surf, reinterpret_cast<EGLContext>(context_));
		} else {
			old_egl_display = EGL_NO_DISPLAY;
			old_egl_ctx = EGL_NO_CONTEXT;
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			glXMakeCurrent(glx_display_, glx_drawable_, glx_context_);
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		glXMakeCurrent(glx_display_, glx_drawable_, glx_context_);
	}
#endif

	mpv_render_context_free(render_context_);

#ifdef _WIN32
	wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (egl_pbuffer_surface_ != EGL_NO_SURFACE) {
			eglDestroySurface(egl_display_, egl_pbuffer_surface_);
			egl_pbuffer_surface_ = EGL_NO_SURFACE;
		}
		egl_draw_surface_ = EGL_NO_SURFACE;
		egl_read_surface_ = EGL_NO_SURFACE;
		// Restore Skiko's EGL context instead of releasing to None
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		} else {
			eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		}
	} else if (using_current_ctx_) {
		// Release our claim on Skiko's context (we don't own the context/window/display)
		glXMakeCurrent(glx_display_, None, None);
	} else {
		// Restore Skiko's GLX context before destroying our pbuffer
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
		if (glx_display_) {
			if (owns_glx_drawable_ && glx_drawable_ != None) glXDestroyPbuffer(glx_display_, glx_drawable_);
			if (owns_glx_context_ && glx_context_) {
				glXDestroyContext(glx_display_, glx_context_);
				glx_context_ = nullptr;
				owns_glx_context_ = false;
			}
			if (owns_glx_display_) XCloseDisplay(glx_display_);
			glx_display_ = nullptr;
			glx_drawable_ = None;
		}
	}
#endif

render_context_ = nullptr;
return true;
}

GLuint mpv_handle_t::create_texture(int width, int height) {
FP;
CHECK_HANDLE_RETURN_INT()
LOCK(texture_lock);

#ifdef _WIN32
HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
if (!wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_))) {
LOG("Failed to make OpenGL context current in create_texture");
return 0;
}
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (old_egl_ctx != reinterpret_cast<EGLContext>(context_)) {
			EGLSurface draw_surf = egl_draw_surface_ != EGL_NO_SURFACE ? egl_draw_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_draw);
			EGLSurface read_surf = egl_read_surface_ != EGL_NO_SURFACE ? egl_read_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_read);
			if (!eglMakeCurrent(egl_display_, draw_surf, read_surf, reinterpret_cast<EGLContext>(context_))) {
				LOG("Failed to make EGL context current in create_texture\n");
				return 0;
			}
		} else {
			old_egl_display = EGL_NO_DISPLAY;
			old_egl_ctx = EGL_NO_CONTEXT;
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
				LOG("Failed to make Skiko's GLX context current in create_texture\n");
				return 0;
			}
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
			LOG("Failed to make GLX context current in create_texture\n");
			return 0;
		}
	}
#endif

GLuint old_texture = texture_;
GLuint old_fbo = fbo_;
GLuint new_texture = GL_ZERO;
GLuint new_fbo = GL_ZERO;

glGenTextures(1, &new_texture);
{ GLenum e = glGetError(); if (e) LOG("[GL] glGenTextures err=0x%x\n", e); }
glBindTexture(GL_TEXTURE_2D, new_texture);
{ GLenum e = glGetError(); if (e) LOG("[GL] glBindTexture err=0x%x\n", e); }
glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
{ GLenum e = glGetError(); if (e) LOG("[GL] glTexImage2D err=0x%x\n", e); }

glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
{ GLenum e = glGetError(); if (e) LOG("[GL] glTexParameteri err=0x%x\n", e); }

pfnGlGenFramebuffers(1, &new_fbo);
{ GLenum e = glGetError(); if (e) LOG("[GL] glGenFramebuffers err=0x%x\n", e); }
pfnGlBindFramebuffer(GL_FRAMEBUFFER, new_fbo);
{ GLenum e = glGetError(); if (e) LOG("[GL] glBindFramebuffer err=0x%x\n", e); }
pfnGlFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
GL_TEXTURE_2D, new_texture, 0);
{ GLenum e = glGetError(); if (e) LOG("[GL] glFramebufferTexture2D err=0x%x\n", e); }

			GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (status != GL_FRAMEBUFFER_COMPLETE) {
				LOG("[GL] create_texture fbo_status=0x%x\n", status);
LOG("Framebuffer not complete in create_texture: 0x%x", status);
release_texture_impl(&new_texture, &new_fbo);
pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif
return 0;
}

texture_ = new_texture;
fbo_ = new_fbo;

width_ = width;
height_ = height;

if (old_texture != GL_ZERO && old_fbo != GL_ZERO) {
release_texture_impl(&old_texture, &old_fbo);
}

pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else if (!using_current_ctx_) {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif

return texture_;
}

bool mpv_handle_t::release_texture() {
FP;
CHECK_HANDLE()
LOCK(texture_lock);

width_ = 0;
height_ = 0;

#ifdef _WIN32
HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_));
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (old_egl_ctx != reinterpret_cast<EGLContext>(context_)) {
			EGLSurface draw_surf = egl_draw_surface_ != EGL_NO_SURFACE ? egl_draw_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_draw);
			EGLSurface read_surf = egl_read_surface_ != EGL_NO_SURFACE ? egl_read_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_read);
			eglMakeCurrent(egl_display_, draw_surf, read_surf, reinterpret_cast<EGLContext>(context_));
		} else {
			old_egl_display = EGL_NO_DISPLAY;
			old_egl_ctx = EGL_NO_CONTEXT;
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			glXMakeCurrent(glx_display_, glx_drawable_, glx_context_);
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		glXMakeCurrent(glx_display_, glx_drawable_, glx_context_);
	}
#endif

bool released = release_texture_impl(&texture_, &fbo_);

#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else if (!using_current_ctx_) {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif

return released;
}

bool release_texture_impl(GLuint* texture_id, GLuint* framebuffer_object) {
if (*texture_id == GL_ZERO || *framebuffer_object == GL_ZERO) return false;

GLuint textures_to_delete[1] = { *texture_id };
GLuint framebuffer_to_delete[1] = { *framebuffer_object };

glDeleteTextures(1, textures_to_delete);
{ GLenum e = glGetError(); if (e) LOG("[GL] glDeleteTextures err=0x%x\n", e); }
pfnGlDeleteFramebuffers(1, framebuffer_to_delete);
{ GLenum e = glGetError(); if (e) LOG("[GL] glDeleteFramebuffers err=0x%x\n", e); }

*texture_id = GL_ZERO;
*framebuffer_object = GL_ZERO;
return true;
}

bool mpv_handle_t::render_frame() {
CHECK_HANDLE()
LOCK(texture_lock);

#ifdef _WIN32
if (!render_context_ || !context_ || !device_ || !fbo_ || !texture_ || !width_ || !height_) {
	LOG("render_frame: null check failed (rc=%p ctx=%p dev=%p fbo=%u tex=%u wxh=%dx%d)",
		(void*)render_context_, (void*)context_, (void*)device_, fbo_, texture_, width_, height_);
	return false;
}
#elif defined(__linux__)
if (!render_context_ || !context_ || !fbo_ || !texture_ || !width_ || !height_) {
	LOG("render_frame: null check failed (rc=%p ctx=%p fbo=%u tex=%u wxh=%dx%d)",
		(void*)render_context_, (void*)context_, fbo_, texture_, width_, height_);
	return false;
}
#endif

#ifdef _WIN32
HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
if (!wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_))) {
LOG("Failed to make OpenGL context current in render_frame");
return false;
}
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (old_egl_ctx != reinterpret_cast<EGLContext>(context_)) {
			EGLSurface draw_surf = egl_draw_surface_ != EGL_NO_SURFACE ? egl_draw_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_draw);
			EGLSurface read_surf = egl_read_surface_ != EGL_NO_SURFACE ? egl_read_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_read);
			if (!eglMakeCurrent(egl_display_, draw_surf, read_surf, reinterpret_cast<EGLContext>(context_))) {
				LOG("Failed to make EGL context current in render_frame\n");
				return false;
			}
		} else {
			old_egl_display = EGL_NO_DISPLAY;
			old_egl_ctx = EGL_NO_CONTEXT;
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
				LOG("Failed to make Skiko's GLX context current in render_frame\n");
				return false;
			}
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
			LOG("Failed to make GLX context current in render_frame\n");
			return false;
		}
	}
#endif

pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
{ GLenum e = glGetError(); if (e) LOG("[GL] render_frame glBindFramebuffer err=0x%x\n", e); }
		GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			LOG("[GL] render_frame fbo_status=0x%x\n", status);
LOG("[GL] Framebuffer not complete in render_frame: 0x%x", status);
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif
return false;
}

// On resize, viewport can stay stale from previous dimensions on some drivers.
// Always force it to current render target size before asking mpv to render.
glViewport(0, 0, width_, height_);
{ GLenum e = glGetError(); if (e) LOG("[GL] render_frame glViewport err=0x%x\n", e); }
glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
glClear(GL_COLOR_BUFFER_BIT);
{ GLenum e = glGetError(); if (e) LOG("[GL] render_frame glClear err=0x%x\n", e); }

mpv_opengl_fbo fbo_params{
static_cast<int>(fbo_), width_, height_, GL_RGBA8
};
mpv_render_param params[] = {
{
MPV_RENDER_PARAM_OPENGL_FBO, &fbo_params
},
{
MPV_RENDER_PARAM_INVALID, nullptr
},
};

// 无论是否有新帧，都调用 render（mpv 文档建议）
	int render_result = mpv_render_context_render(render_context_, params);
	if (render_result < 0) {
		LOG("mpv_render_context_render failed: %d", render_result);
	}
	{ GLenum e = glGetError(); if (e) LOG("[GL] mpv_render_context_render err=0x%x\n", e); }

	// 解绑 FBO
	pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);
	{ GLenum e = glGetError(); if (e) LOG("[GL] render_frame unbind FBO err=0x%x\n", e); }

	glFinish();
	{ GLenum e = glGetError(); if (e) LOG("[GL] glFinish err=0x%x\n", e); }
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else if (!using_current_ctx_) {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif

return render_result >= 0;
}

bool mpv_handle_t::debug_render_solid(float red, float green, float blue, float alpha) {
CHECK_HANDLE()
LOCK(texture_lock);

#ifdef _WIN32
if (!context_ || !device_ || !fbo_ || !texture_ || !width_ || !height_)
return false;
#elif defined(__linux__)
if (!context_ || !fbo_ || !texture_ || !width_ || !height_)
return false;
#endif

#ifdef _WIN32
HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
if (!wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_))) {
LOG("Failed to make OpenGL context current in debug_render_solid");
return false;
}
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (old_egl_ctx != reinterpret_cast<EGLContext>(context_)) {
			EGLSurface draw_surf = egl_draw_surface_ != EGL_NO_SURFACE ? egl_draw_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_draw);
			EGLSurface read_surf = egl_read_surface_ != EGL_NO_SURFACE ? egl_read_surface_ : (egl_pbuffer_surface_ != EGL_NO_SURFACE ? egl_pbuffer_surface_ : old_egl_read);
			if (!eglMakeCurrent(egl_display_, draw_surf, read_surf, reinterpret_cast<EGLContext>(context_))) {
				LOG("Failed to make EGL context current in debug_render_solid\n");
				return false;
			}
		} else {
			old_egl_display = EGL_NO_DISPLAY;
			old_egl_ctx = EGL_NO_CONTEXT;
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
				LOG("Failed to make Skiko's GLX context current in debug_render_solid\n");
				return false;
			}
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
			LOG("Failed to make GLX context current in debug_render_solid\n");
			return false;
		}
	}
#endif

pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
{ GLenum e = glGetError(); if (e) LOG("[GL] debug_render_solid glBindFramebuffer err=0x%x\n", e); }
		GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			LOG("[GL] debug_render_solid fbo_status=0x%x\n", status);
LOG("[GL] Framebuffer not complete in debug_render_solid: 0x%x", status);
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif
return false;
}

glViewport(0, 0, width_, height_);
glClearColor(red, green, blue, alpha);
glClear(GL_COLOR_BUFFER_BIT);
{ GLenum e = glGetError(); if (e) LOG("[GL] debug_render_solid glClear err=0x%x\n", e); }
pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);
glFinish();
{ GLenum e = glGetError(); if (e) LOG("[GL] glFinish err=0x%x\n", e); }
#ifdef _WIN32
wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif

return true;
}

std::string mpv_handle_t::read_texture_stats() {
LOCK(texture_lock);

#ifdef _WIN32
if (!context_ || !device_ || !fbo_ || !texture_ || !width_ || !height_)
return "unavailable";
#elif defined(__linux__)
if (!context_ || !fbo_ || !texture_ || !width_ || !height_)
return "unavailable";
#endif

#ifdef _WIN32
HDC old_dc = wglGetCurrentDC();
HGLRC old_ctx = wglGetCurrentContext();
if (!wglMakeCurrent(device_, reinterpret_cast<HGLRC>(context_))) {
return "wglMakeCurrent=false";
}
#elif defined(__linux__)
	Display* old_glx_dpy = nullptr;
	GLXDrawable old_glx_drawable = None;
	GLXContext old_glx_ctx = nullptr;
	EGLDisplay old_egl_display = EGL_NO_DISPLAY;
	EGLContext old_egl_ctx = EGL_NO_CONTEXT;
	EGLSurface old_egl_draw = EGL_NO_SURFACE;
	EGLSurface old_egl_read = EGL_NO_SURFACE;

	if (using_egl_) {
		old_egl_display = eglGetCurrentDisplay();
		old_egl_ctx = eglGetCurrentContext();
		old_egl_draw = eglGetCurrentSurface(EGL_DRAW);
		old_egl_read = eglGetCurrentSurface(EGL_READ);
		if (!eglMakeCurrent(egl_display_, EGL_NO_SURFACE, EGL_NO_SURFACE, reinterpret_cast<EGLContext>(context_))) {
			return "eglMakeCurrent=false";
		}
	} else if (using_current_ctx_) {
		GLXContext cur_glx = glXGetCurrentContext();
		if (cur_glx != glx_context_) {
			if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
				return "glXMakeCurrent=false";
			}
		}
	} else {
		old_glx_dpy = glXGetCurrentDisplay();
		old_glx_drawable = glXGetCurrentDrawable();
		old_glx_ctx = glXGetCurrentContext();
		if (!glXMakeCurrent(glx_display_, glx_drawable_, glx_context_)) {
			return "glXMakeCurrent=false";
		}
	}
#endif

pfnGlBindFramebuffer(GL_FRAMEBUFFER, fbo_);
	GLenum status = pfnGlCheckFramebufferStatus(GL_FRAMEBUFFER);
	if (status != GL_FRAMEBUFFER_COMPLETE) {
		std::ostringstream failed;
		failed << "fboStatus=0x" << std::hex << status;
#ifdef _WIN32
		wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
		if (using_egl_) {
			if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
				eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
			}
		} else if (!using_current_ctx_) {
			if (old_glx_dpy && old_glx_ctx) {
				glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
			}
		}
#endif
		return failed.str();
	}

	int sample_width = width_ < 64 ? width_ : 64;
	int sample_height = height_ < 64 ? height_ : 64;
	std::vector<unsigned char> pixels(sample_width * sample_height * 4);
	glReadPixels(0, 0, sample_width, sample_height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());

	long long sum_r = 0;
	long long sum_g = 0;
	long long sum_b = 0;
	long long non_black = 0;
	for (int i = 0; i < sample_width * sample_height; ++i) {
		unsigned char r = pixels[i * 4];
		unsigned char g = pixels[i * 4 + 1];
		unsigned char b = pixels[i * 4 + 2];
		sum_r += r;
		sum_g += g;
		sum_b += b;
		if (r > 3 || g > 3 || b > 3) {
			non_black++;
		}
	}

	pfnGlBindFramebuffer(GL_FRAMEBUFFER, 0);
#ifdef _WIN32
	wglMakeCurrent(old_dc, old_ctx);
#elif defined(__linux__)
	if (using_egl_) {
		if (old_egl_display != EGL_NO_DISPLAY && old_egl_ctx != EGL_NO_CONTEXT) {
			eglMakeCurrent(old_egl_display, old_egl_draw, old_egl_read, old_egl_ctx);
		}
	} else if (!using_current_ctx_) {
		if (old_glx_dpy && old_glx_ctx) {
			glXMakeCurrent(old_glx_dpy, old_glx_drawable, old_glx_ctx);
		}
	}
#endif

int count = sample_width * sample_height;
std::ostringstream result;
result << "size=" << width_ << "x" << height_
<< " sample=" << sample_width << "x" << sample_height
<< " avgRgb=" << (sum_r / count) << "," << (sum_g / count) << "," << (sum_b / count)
<< " nonBlack=" << non_black << "/" << count;
	return result.str();
}
#endif

bool mpv_handle_t::destroy(JNIEnv *env) {
FP;
CHECK_HANDLE()

event_loop_request_exit = true;
mpv_wakeup(handle_);

if (!event_thread_) {
LOG("event thread is not created when destroy mpv handle");
return false;
}
event_thread_->join();

if (event_listener_) env->DeleteGlobalRef(*event_listener_);
mpv_terminate_destroy(handle_);

return true;
}

} // namespace mediampv
