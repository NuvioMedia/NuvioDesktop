#define _GNU_SOURCE
#include <jni.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>
#include <math.h>
#include <locale.h>
#include <unistd.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <fcntl.h>
#include <gbm.h>

/* ------------------------------------------------------------------ */
/*  Debug logging                                                      */
/* ------------------------------------------------------------------ */
#define DBG(...)  do { \
    fprintf(stderr, "[player_bridge] " __VA_ARGS__); \
    fflush(stderr); \
} while (0)

__attribute__((constructor))
static void on_load(void) {
    fprintf(stderr, "[player_bridge] CONSTRUCTOR: .so loaded (built %s %s)\n",
            __DATE__, __TIME__);
}

/* ------------------------------------------------------------------ */
/*  Cached GL offscreen instance (reused between player sessions)      */
/* ------------------------------------------------------------------ */
static pthread_mutex_t glCacheMutex = PTHREAD_MUTEX_INITIALIZER;
static struct {
    int valid;
    int gbmFd;
    struct gbm_device *gbmDevice;
    EGLDisplay eglDisplay;
    EGLContext eglContext;
    mpv_handle *mpv;
    mpv_render_context *renderCtx;
    GLuint fbo;
    GLuint fboTex;
    int fboW;
    int fboH;
} glCache = {0};

/* ------------------------------------------------------------------ */
/*  Per-instance state                                                 */
/* ------------------------------------------------------------------ */
typedef struct {
    JavaVM *jvm;
    jobject eventSink;
    jmethodID eventMethod;
    mpv_handle *mpv;
    mpv_render_context *renderCtx;
    char *sourceUrl;
    char **headers;
    int nheaders;
    volatile int alive;
    int gpuMode; /* 0 = SW rendering, 2 = GL offscreen (EGL FBO) */

    /* SW mode (gpuMode 0) */
    pthread_t renderThread;
    pthread_mutex_t frameMutex;
    int frameW;
    int frameH;
    int frameStride;
    char *frameData;
    volatile int frameReady;

    /* Target display size */
    volatile int targetW;
    volatile int targetH;

    /* Frame-ready signaling from mpv */
    pthread_cond_t frameCond;
    volatile int frameSignal;

    /* GL offscreen mode (gpuMode 2) */
    int gbmFd;
    struct gbm_device *gbmDevice;
    EGLDisplay eglDisplay;
    EGLContext eglContext;
    EGLSurface eglSurface;
    GLuint fbo;
    GLuint fboTex;
    int fboW;
    int fboH;

    /* Captured from JNI thread for shared context fallback (NVIDIA) */
    EGLDisplay skiaDisplay;
} CreateTask;

static void callEventSink(JNIEnv *env, JavaVM *jvm,
    jobject eventSink, jmethodID eventMethod,
    const char *type, double value) {
    if (!eventSink || !eventMethod) return;
    int detach = 0;
    if (!env) {
        jint ret = (*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            (*jvm)->AttachCurrentThread(jvm, (void**)&env, NULL);
            detach = 1;
        }
    }
    if (env) {
        jstring jType = (*env)->NewStringUTF(env, type);
        (*env)->CallVoidMethod(env, eventSink, eventMethod, jType, value);
        (*env)->DeleteLocalRef(env, jType);
    }
    if (detach) {
        (*jvm)->DetachCurrentThread(jvm);
    }
}

/* ------------------------------------------------------------------ */
/*  EGL offscreen context via GBM                                      */
/* ------------------------------------------------------------------ */
#ifndef EGL_PLATFORM_GBM_KHR
#define EGL_PLATFORM_GBM_KHR 0x31D7
#endif

#ifndef EGL_PLATFORM_DEVICE_EXT
#define EGL_PLATFORM_DEVICE_EXT 0x313F
#endif

#ifndef EGL_EXT_platform_base
typedef EGLDisplay (*PFNEGLGETPLATFORMDISPLAYEXTPROC)(EGLenum, void *, const EGLint *);
#endif

/* Use system-provided PFNEGLQUERYDEVICESEXTPROC from eglext.h if available */
#ifndef EGL_EXT_device_enumeration
typedef EGLBoolean (*PFNEGLQUERYDEVICESEXTPROC)(EGLint, void *, EGLint *);
#endif

/* ------------------------------------------------------------------ */
/*  EGL via NVIDIA vendor library (bypasses Mesa dispatcher)           */
/* ------------------------------------------------------------------ */
typedef EGLDisplay (*PFN_eglGetPlatformDisplay)(EGLenum, void*, const EGLint*);
typedef EGLBoolean (*PFN_eglInitialize)(EGLDisplay, EGLint*, EGLint*);
typedef EGLBoolean (*PFN_eglBindAPI)(EGLenum);
typedef EGLBoolean (*PFN_eglChooseConfig)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
typedef EGLContext (*PFN_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
typedef EGLSurface (*PFN_eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint*);
typedef EGLBoolean (*PFN_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*PFN_eglQueryDevicesEXT)(EGLint, EGLDeviceEXT*, EGLint*);
typedef void* (*PFN_eglGetProcAddress)(const char*);
typedef EGLint (*PFN_eglGetError)(void);
typedef EGLBoolean (*PFN_eglReleaseThread)(void);

static int initEGL_NvidiaVendor(CreateTask *task) {
    /* Try to load NVIDIA's vendor-specific EGL library directly.
     * This bypasses the Mesa GLVND dispatcher which may route to the wrong implementation. */
    static const char *nvLibPaths[] = {
        "libEGL_nvidia.so.0",
        "libEGL_nvidia.so",
        "/usr/lib/x86_64-linux-gnu/libEGL_nvidia.so.0",
        "/usr/lib64/libEGL_nvidia.so.0",
        NULL
    };

    void *nvEGL = NULL;
    for (int i = 0; nvLibPaths[i]; i++) {
        nvEGL = dlopen(nvLibPaths[i], RTLD_NOW);
        if (nvEGL) {
            DBG("EGL: loaded NVIDIA vendor library: %s\n", nvLibPaths[i]);
            break;
        }
    }
    if (!nvEGL) {
        DBG("EGL: NVIDIA vendor library not found\n");
        return 0;
    }

    /* Resolve EGL functions from NVIDIA library */
    PFN_eglGetPlatformDisplay nv_eglGetPlatformDisplay =
        (PFN_eglGetPlatformDisplay)dlsym(nvEGL, "eglGetPlatformDisplay");
    PFN_eglInitialize nv_eglInitialize = (PFN_eglInitialize)dlsym(nvEGL, "eglInitialize");
    PFN_eglBindAPI nv_eglBindAPI = (PFN_eglBindAPI)dlsym(nvEGL, "eglBindAPI");
    PFN_eglChooseConfig nv_eglChooseConfig = (PFN_eglChooseConfig)dlsym(nvEGL, "eglChooseConfig");
    PFN_eglCreateContext nv_eglCreateContext = (PFN_eglCreateContext)dlsym(nvEGL, "eglCreateContext");
    PFN_eglCreatePbufferSurface nv_eglCreatePbufferSurface = (PFN_eglCreatePbufferSurface)dlsym(nvEGL, "eglCreatePbufferSurface");
    PFN_eglMakeCurrent nv_eglMakeCurrent = (PFN_eglMakeCurrent)dlsym(nvEGL, "eglMakeCurrent");
    PFN_eglQueryDevicesEXT nv_eglQueryDevicesEXT = (PFN_eglQueryDevicesEXT)dlsym(nvEGL, "eglQueryDevicesEXT");
    PFN_eglGetProcAddress nv_eglGetProcAddress = (PFN_eglGetProcAddress)dlsym(nvEGL, "eglGetProcAddress");
    PFN_eglGetError nv_eglGetError = (PFN_eglGetError)dlsym(nvEGL, "eglGetError");
    PFN_eglReleaseThread nv_eglReleaseThread = (PFN_eglReleaseThread)dlsym(nvEGL, "eglReleaseThread");

    if (!nv_eglGetPlatformDisplay || !nv_eglInitialize || !nv_eglMakeCurrent || !nv_eglCreateContext) {
        DBG("EGL: NVIDIA vendor lib missing required symbols, trying GLVND dispatch override\n");
        dlclose(nvEGL);

        /* Alternative: set __EGL_VENDOR_LIBRARY_FILENAMES to force NVIDIA vendor in GLVND.
         * Then use standard EGL functions which will be dispatched to NVIDIA. */
        static const char *nvJsonPaths[] = {
            "/usr/share/glvnd/egl_vendor.d/10_nvidia.json",
            "/usr/share/egl/egl_external_platform.d/10_nvidia.json",
            "/etc/glvnd/egl_vendor.d/10_nvidia.json",
            NULL
        };
        const char *nvJson = NULL;
        for (int i = 0; nvJsonPaths[i]; i++) {
            if (access(nvJsonPaths[i], R_OK) == 0) {
                nvJson = nvJsonPaths[i];
                break;
            }
        }
        if (!nvJson) {
            DBG("EGL: NVIDIA GLVND vendor JSON not found\n");
            return 0;
        }
        DBG("EGL: overriding EGL vendor to: %s\n", nvJson);

        /* Save and override */
        const char *oldVendor = getenv("__EGL_VENDOR_LIBRARY_FILENAMES");
        char oldVendorBuf[512] = {0};
        if (oldVendor) strncpy(oldVendorBuf, oldVendor, sizeof(oldVendorBuf)-1);
        setenv("__EGL_VENDOR_LIBRARY_FILENAMES", nvJson, 1);

        /* Now standard EGL calls should go through NVIDIA */
        eglReleaseThread();

        /* Open GBM device for NVIDIA render node */
        int nvFd = -1;
        static const char *nvRenderNodes[] = {"/dev/dri/renderD129", "/dev/dri/renderD128", "/dev/dri/renderD130", NULL};
        for (int i = 0; nvRenderNodes[i]; i++) {
            nvFd = open(nvRenderNodes[i], O_RDWR);
            if (nvFd >= 0) {
                DBG("EGL: trying render node %s (fd=%d)\n", nvRenderNodes[i], nvFd);
                break;
            }
        }
        if (nvFd < 0) {
            DBG("EGL: no render node available\n");
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        struct gbm_device *gbmDev = gbm_create_device(nvFd);
        if (!gbmDev) {
            DBG("EGL: GBM device creation failed for NVIDIA override\n");
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatDisp =
            (PFNEGLGETPLATFORMDISPLAYEXTPROC)(void*)eglGetProcAddress("eglGetPlatformDisplayEXT");
        EGLDisplay display = EGL_NO_DISPLAY;
        if (getPlatDisp) {
            display = getPlatDisp(EGL_PLATFORM_GBM_KHR, gbmDev, NULL);
        }
        if (display == EGL_NO_DISPLAY) {
            display = eglGetDisplay((EGLNativeDisplayType)gbmDev);
        }
        if (display == EGL_NO_DISPLAY) {
            DBG("EGL: NVIDIA override display failed\n");
            gbm_device_destroy(gbmDev);
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        EGLint major, minor;
        if (!eglInitialize(display, &major, &minor)) {
            DBG("EGL: NVIDIA override eglInitialize failed\n");
            gbm_device_destroy(gbmDev);
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }
        DBG("EGL: NVIDIA override initialized %d.%d\n", major, minor);

        eglBindAPI(EGL_OPENGL_API);
        EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        EGLConfig config;
        EGLint numConfigs;
        if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            DBG("EGL: NVIDIA override config failed\n");
            eglTerminate(display);
            gbm_device_destroy(gbmDev);
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                                EGL_NONE };
        EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
        if (ctx == EGL_NO_CONTEXT) {
            EGLint ctxAttribs2[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
            ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
        if (ctx == EGL_NO_CONTEXT) {
            DBG("EGL: NVIDIA override context failed\n");
            eglTerminate(display);
            gbm_device_destroy(gbmDev);
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        EGLSurface surface = eglCreatePbufferSurface(display, config, pbufAttribs);
        EGLSurface testSurf = (surface != EGL_NO_SURFACE) ? surface : EGL_NO_SURFACE;

        if (!eglMakeCurrent(display, testSurf, testSurf, ctx)) {
            DBG("EGL: NVIDIA override eglMakeCurrent failed (err=0x%x)\n", eglGetError());
            eglDestroyContext(display, ctx);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
            gbm_device_destroy(gbmDev);
            close(nvFd);
            if (oldVendorBuf[0]) setenv("__EGL_VENDOR_LIBRARY_FILENAMES", oldVendorBuf, 1);
            else unsetenv("__EGL_VENDOR_LIBRARY_FILENAMES");
            return 0;
        }

        /* Success! */
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        task->eglDisplay = display;
        task->eglContext = ctx;
        task->eglSurface = surface;
        task->gbmFd = nvFd;
        task->gbmDevice = gbmDev;
        DBG("EGL: NVIDIA GLVND override context ready (display=%p, surface=%s)\n",
            (void*)display, surface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless");
        /* Keep vendor override active for this process */
        return 1;
    }

    if (nv_eglReleaseThread) nv_eglReleaseThread();

    /* Get NVIDIA EGL device and create platform display */
    EGLDisplay display = EGL_NO_DISPLAY;

    if (nv_eglQueryDevicesEXT) {
        EGLDeviceEXT devices[4];
        EGLint numDevices = 0;
        if (nv_eglQueryDevicesEXT(4, devices, &numDevices) && numDevices > 0) {
            /* EGL_PLATFORM_DEVICE_EXT through NVIDIA's own library */
            display = nv_eglGetPlatformDisplay(EGL_PLATFORM_DEVICE_EXT, devices[0], NULL);
            DBG("EGL: NVIDIA vendor device display=%p (from %d devices)\n", (void*)display, numDevices);
        }
    }

    if (display == EGL_NO_DISPLAY) {
        /* Fallback to GBM platform through NVIDIA */
        if (task->gbmDevice) {
            display = nv_eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, task->gbmDevice, NULL);
            DBG("EGL: NVIDIA vendor GBM display=%p\n", (void*)display);
        }
    }

    if (display == EGL_NO_DISPLAY) {
        DBG("EGL: NVIDIA vendor could not get display\n");
        dlclose(nvEGL);
        return 0;
    }

    EGLint major, minor;
    if (!nv_eglInitialize(display, &major, &minor)) {
        DBG("EGL: NVIDIA vendor eglInitialize failed\n");
        dlclose(nvEGL);
        return 0;
    }
    DBG("EGL: NVIDIA vendor initialized %d.%d\n", major, minor);

    if (nv_eglBindAPI) nv_eglBindAPI(EGL_OPENGL_API);

    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    if (!nv_eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        DBG("EGL: NVIDIA vendor chooseConfig failed\n");
        dlclose(nvEGL);
        return 0;
    }

    EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                            EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                            EGL_NONE };
    EGLContext ctx = nv_eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
    if (ctx == EGL_NO_CONTEXT) {
        EGLint ctxAttribs2[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
        ctx = nv_eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs2);
    }
    if (ctx == EGL_NO_CONTEXT) {
        DBG("EGL: NVIDIA vendor context creation failed (err=0x%x)\n", nv_eglGetError ? nv_eglGetError() : 0);
        dlclose(nvEGL);
        return 0;
    }

    EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surface = nv_eglCreatePbufferSurface(display, config, pbufAttribs);
    if (surface == EGL_NO_SURFACE) {
        DBG("EGL: NVIDIA vendor pbuffer failed, trying surfaceless\n");
        surface = EGL_NO_SURFACE;
    }

    /* Test MakeCurrent */
    EGLSurface testSurf = (surface != EGL_NO_SURFACE) ? surface : EGL_NO_SURFACE;
    if (!nv_eglMakeCurrent(display, testSurf, testSurf, ctx)) {
        DBG("EGL: NVIDIA vendor eglMakeCurrent failed (err=0x%x)\n", nv_eglGetError ? nv_eglGetError() : 0);
        dlclose(nvEGL);
        return 0;
    }

    /* Success! Unbind and store */
    nv_eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    task->eglDisplay = display;
    task->eglContext = ctx;
    task->eglSurface = surface;
    DBG("EGL: NVIDIA vendor context ready (display=%p, surface=%s)\n",
        (void*)display, surface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless");

    /* NOTE: we intentionally keep nvEGL handle open (leaked) — the display/context
     * depend on this library being loaded for the process lifetime. */
    return 1;
}

/* ------------------------------------------------------------------ */
/*  EGL shared context (piggyback on Skia/Compose's EGLDisplay)        */
/* ------------------------------------------------------------------ */
static int initEGL_SharedContext(CreateTask *task) {
    DBG("EGL: trying shared context with Skia's EGLDisplay\n");

    /* Use the display captured from JNI thread (where Skia may have been active) */
    EGLDisplay skiaDisplay = task->skiaDisplay;

    DBG("EGL: skiaDisplay=%p (captured from JNI thread)\n", (void*)skiaDisplay);

    if (skiaDisplay == EGL_NO_DISPLAY) {
        /* Last resort: try default display */
        skiaDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (skiaDisplay == EGL_NO_DISPLAY) {
            DBG("EGL: no usable display found for shared context\n");
            return 0;
        }
    }

    /* Ensure it's initialized (idempotent if already initialized by Skia) */
    EGLint major, minor;
    if (!eglInitialize(skiaDisplay, &major, &minor)) {
        DBG("EGL: shared display init failed\n");
        return 0;
    }
    DBG("EGL: shared display initialized %d.%d\n", major, minor);

    task->eglDisplay = skiaDisplay;

    /* Bind GL API (Skia may use GLES — try GL first, fallback GLES) */
    eglBindAPI(EGL_OPENGL_API);

    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    int useGLES = 0;
    if (!eglChooseConfig(task->eglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        DBG("EGL: shared GL config failed, trying GLES\n");
        eglBindAPI(EGL_OPENGL_ES_API);
        EGLint glesAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        if (!eglChooseConfig(task->eglDisplay, glesAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            DBG("EGL: shared context: no suitable config\n");
            task->eglDisplay = EGL_NO_DISPLAY;
            return 0;
        }
        useGLES = 1;
    }

    /* Create context on the shared display — no share with Skia's context
     * (we don't have access to it from render thread, but using the same
     * display should bypass NVIDIA's multi-display restriction) */
    EGLContext ctx = EGL_NO_CONTEXT;

    if (!useGLES) {
        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                                EGL_NONE };
        ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        if (ctx == EGL_NO_CONTEXT) {
            EGLint ctxAttribs2[] = { EGL_NONE };
            ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
    } else {
        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
        ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
    }

    if (ctx == EGL_NO_CONTEXT) {
        DBG("EGL: shared context creation failed (err=0x%x)\n", eglGetError());
        task->eglDisplay = EGL_NO_DISPLAY;
        return 0;
    }
    task->eglContext = ctx;
    DBG("EGL: shared context created (api=%s)\n", useGLES ? "GLES" : "GL");

    /* Create pbuffer surface */
    EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    task->eglSurface = eglCreatePbufferSurface(task->eglDisplay, config, pbufAttribs);
    if (task->eglSurface == EGL_NO_SURFACE) {
        DBG("EGL: shared pbuffer failed, will use surfaceless\n");
        task->eglSurface = EGL_NO_SURFACE;
    }

    /* DO NOT test eglMakeCurrent here — we're on the JNI thread where Skia's context
     * may be active. The render thread will do MakeCurrent. Just verify context was created. */
    DBG("EGL: shared context ready (display=%p, surface=%s)\n",
        (void*)task->eglDisplay,
        task->eglSurface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless");
    return 1;
}

/* ------------------------------------------------------------------ */
/*  EGL Device Platform fallback (headless, works on NVIDIA without    */
/*  GBM surfaceless issues)                                            */
/* ------------------------------------------------------------------ */
static int initEGL_DevicePlatform(CreateTask *task) {
    DBG("EGL: trying EGL_PLATFORM_DEVICE_EXT fallback\n");

    /* Clear any stale EGL thread state (NVIDIA keeps per-thread state) */
    eglReleaseThread();

    PFNEGLQUERYDEVICESEXTPROC eglQueryDevicesEXT =
        (PFNEGLQUERYDEVICESEXTPROC)(void*)eglGetProcAddress("eglQueryDevicesEXT");
    PFNEGLGETPLATFORMDISPLAYEXTPROC eglGetPlatformDisplayEXT =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)(void*)eglGetProcAddress("eglGetPlatformDisplayEXT");

    if (!eglQueryDevicesEXT || !eglGetPlatformDisplayEXT) {
        DBG("EGL: Device Platform extensions not available\n");
        return 0;
    }

    /* EGLDeviceEXT is void* — query available devices */
    EGLDeviceEXT devices[8];
    EGLint numDevices = 0;
    if (!eglQueryDevicesEXT(8, devices, &numDevices) || numDevices == 0) {
        DBG("EGL: eglQueryDevicesEXT found no devices\n");
        return 0;
    }
    DBG("EGL: found %d EGL devices\n", numDevices);

    for (int i = 0; i < numDevices; i++) {
        task->eglDisplay = eglGetPlatformDisplayEXT(EGL_PLATFORM_DEVICE_EXT, devices[i], NULL);
        if (task->eglDisplay == EGL_NO_DISPLAY) continue;

        EGLint major, minor;
        if (!eglInitialize(task->eglDisplay, &major, &minor)) {
            task->eglDisplay = EGL_NO_DISPLAY;
            continue;
        }
        DBG("EGL: Device[%d] initialized %d.%d\n", i, major, minor);

        /* Log client extensions for diagnostics */
        const char *exts = eglQueryString(task->eglDisplay, EGL_EXTENSIONS);
        int hasSurfaceless = exts && strstr(exts, "EGL_KHR_surfaceless_context") != NULL;
        int hasCreateCtx = exts && strstr(exts, "EGL_KHR_create_context") != NULL;
        DBG("EGL: Device[%d] surfaceless=%d create_context=%d\n", i, hasSurfaceless, hasCreateCtx);

        /* Bind OpenGL (NVIDIA device platform supports full GL with pbuffer) */
        eglBindAPI(EGL_OPENGL_API);

        EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        EGLConfig config;
        EGLint numConfigs;
        if (!eglChooseConfig(task->eglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            DBG("EGL: Device[%d] GL config failed, trying GLES\n", i);
            eglBindAPI(EGL_OPENGL_ES_API);
            EGLint glesAttribs[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_NONE
            };
            if (!eglChooseConfig(task->eglDisplay, glesAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
                eglTerminate(task->eglDisplay);
                task->eglDisplay = EGL_NO_DISPLAY;
                continue;
            }
        }

        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                                EGL_NONE };
        task->eglContext = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        if (task->eglContext == EGL_NO_CONTEXT) {
            EGLint ctxAttribs2[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
            task->eglContext = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
        if (task->eglContext == EGL_NO_CONTEXT) {
            DBG("EGL: Device[%d] context creation failed\n", i);
            eglTerminate(task->eglDisplay);
            task->eglDisplay = EGL_NO_DISPLAY;
            continue;
        }

        /* Create 1x1 pbuffer — NVIDIA device platform supports this reliably */
        EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        task->eglSurface = eglCreatePbufferSurface(task->eglDisplay, config, pbufAttribs);
        if (task->eglSurface == EGL_NO_SURFACE) {
            DBG("EGL: Device[%d] pbuffer failed (err=0x%x)\n", i, eglGetError());
            task->eglSurface = EGL_NO_SURFACE;
        } else {
            DBG("EGL: Device[%d] pbuffer created OK\n", i);
        }

        /* Verify MakeCurrent works — try with pbuffer first, then surfaceless */
        EGLSurface testSurf = (task->eglSurface != EGL_NO_SURFACE) ? task->eglSurface : EGL_NO_SURFACE;
        if (!eglMakeCurrent(task->eglDisplay, testSurf, testSurf, task->eglContext)) {
            EGLint mkErr = eglGetError();
            DBG("EGL: Device[%d] eglMakeCurrent failed (surface=%s, err=0x%x)\n",
                i, testSurf != EGL_NO_SURFACE ? "pbuffer" : "surfaceless", mkErr);

            /* If we had pbuffer and it failed, try surfaceless */
            if (testSurf != EGL_NO_SURFACE) {
                eglDestroySurface(task->eglDisplay, task->eglSurface);
                task->eglSurface = EGL_NO_SURFACE;
                if (eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, task->eglContext)) {
                    DBG("EGL: Device[%d] surfaceless MakeCurrent OK\n", i);
                    goto device_success;
                }
                DBG("EGL: Device[%d] surfaceless also failed (err=0x%x)\n", i, eglGetError());
            }

            /* Try switching to GLES API if we were using GL */
            eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroyContext(task->eglDisplay, task->eglContext);
            task->eglContext = EGL_NO_CONTEXT;

            DBG("EGL: Device[%d] retrying with GLES API\n", i);
            eglBindAPI(EGL_OPENGL_ES_API);
            EGLint glesRetryAttribs[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_NONE
            };
            EGLConfig glesConfig;
            EGLint glesNumConfigs;
            if (eglChooseConfig(task->eglDisplay, glesRetryAttribs, &glesConfig, 1, &glesNumConfigs) && glesNumConfigs > 0) {
                EGLint glesCtxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
                task->eglContext = eglCreateContext(task->eglDisplay, glesConfig, EGL_NO_CONTEXT, glesCtxAttribs);
                if (task->eglContext != EGL_NO_CONTEXT) {
                    EGLint pb[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
                    task->eglSurface = eglCreatePbufferSurface(task->eglDisplay, glesConfig, pb);
                    EGLSurface s = (task->eglSurface != EGL_NO_SURFACE) ? task->eglSurface : EGL_NO_SURFACE;
                    if (eglMakeCurrent(task->eglDisplay, s, s, task->eglContext)) {
                        DBG("EGL: Device[%d] GLES+pbuffer MakeCurrent OK\n", i);
                        goto device_success;
                    }
                    /* Try surfaceless with GLES */
                    if (task->eglSurface != EGL_NO_SURFACE) {
                        eglDestroySurface(task->eglDisplay, task->eglSurface);
                        task->eglSurface = EGL_NO_SURFACE;
                    }
                    if (eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, task->eglContext)) {
                        DBG("EGL: Device[%d] GLES+surfaceless MakeCurrent OK\n", i);
                        goto device_success;
                    }
                    DBG("EGL: Device[%d] GLES MakeCurrent also failed (err=0x%x)\n", i, eglGetError());
                    eglDestroyContext(task->eglDisplay, task->eglContext);
                    task->eglContext = EGL_NO_CONTEXT;
                }
            }

            eglTerminate(task->eglDisplay);
            task->eglDisplay = EGL_NO_DISPLAY;
            task->eglContext = EGL_NO_CONTEXT;
            task->eglSurface = EGL_NO_SURFACE;
            continue;
        }

device_success:
        /* SUCCESS! Unbind — render thread will rebind */
        eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        DBG("EGL: Device Platform context created successfully (device=%d, surface=%s)\n",
            i, task->eglSurface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless");
        return 1;
    }

    DBG("EGL: all Device Platform attempts failed\n");
    return 0;
}

static int initEGL(CreateTask *task) {
    /* Clear any stale EGL thread state from Skia/Compose */
    eglReleaseThread();

    /* Try render nodes — on multi-GPU systems, find one that works.
     * renderD128 may be AMD iGPU while renderD129 is NVIDIA dGPU. */
    static const char *renderNodes[] = {
        "/dev/dri/renderD128",
        "/dev/dri/renderD129",
        "/dev/dri/renderD130",
        NULL
    };

    for (int nodeIdx = 0; renderNodes[nodeIdx]; nodeIdx++) {
        int origFd = open(renderNodes[nodeIdx], O_RDWR);
        if (origFd < 0) continue;

        task->gbmFd = dup(origFd);
        close(origFd);
        if (task->gbmFd < 0) continue;

        task->gbmDevice = gbm_create_device(task->gbmFd);
        if (!task->gbmDevice) {
            close(task->gbmFd);
            task->gbmFd = -1;
            continue;
        }
        DBG("EGL: trying render node %s\n", renderNodes[nodeIdx]);

    PFNEGLGETPLATFORMDISPLAYEXTPROC eglGetPlatformDisplayEXT =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");

    if (eglGetPlatformDisplayEXT) {
        task->eglDisplay = eglGetPlatformDisplayEXT(EGL_PLATFORM_GBM_KHR, task->gbmDevice, NULL);
    } else {
        task->eglDisplay = eglGetDisplay((EGLNativeDisplayType)task->gbmDevice);
    }

    if (task->eglDisplay == EGL_NO_DISPLAY) {
        DBG("EGL: failed to get EGL display from GBM\n");
        gbm_device_destroy(task->gbmDevice);
        close(task->gbmFd);
        task->gbmFd = -1;
        task->gbmDevice = NULL;
        continue;
    }

    EGLint major, minor;
    if (!eglInitialize(task->eglDisplay, &major, &minor)) {
        DBG("EGL: eglInitialize failed\n");
        gbm_device_destroy(task->gbmDevice);
        close(task->gbmFd);
        task->gbmFd = -1;
        task->gbmDevice = NULL;
        task->eglDisplay = EGL_NO_DISPLAY;
        continue;
    }
    DBG("EGL: initialized %d.%d via GBM\n", major, minor);

    /* Try GLES first — NVIDIA proprietary EGL doesn't support Desktop GL
     * with pbuffer/surfaceless contexts. GLES works on all GPUs. */
    int useGLES = 0;
    EGLConfig config;
    EGLint numConfigs;

    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint glesConfigAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    if (eglChooseConfig(task->eglDisplay, glesConfigAttribs, &config, 1, &numConfigs) && numConfigs > 0) {
        useGLES = 1;
    } else {
        /* Fallback to Desktop GL (Intel/AMD without GLES3 — unlikely but safe) */
        eglBindAPI(EGL_OPENGL_API);
        EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        if (!eglChooseConfig(task->eglDisplay, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
            DBG("EGL: eglChooseConfig failed (both GLES and GL)\n");
            eglTerminate(task->eglDisplay);
            gbm_device_destroy(task->gbmDevice);
            close(task->gbmFd);
            task->gbmFd = -1;
            task->gbmDevice = NULL;
            task->eglDisplay = EGL_NO_DISPLAY;
            continue;
        }
    }

    EGLContext ctx = EGL_NO_CONTEXT;
    if (!useGLES) {
        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                                EGL_NONE };
        ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
        if (ctx == EGL_NO_CONTEXT) {
            EGLint ctxAttribs2[] = { EGL_NONE };
            ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs2);
        }
    } else {
        EGLint ctxAttribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
        ctx = eglCreateContext(task->eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
    }
    if (ctx == EGL_NO_CONTEXT) {
        DBG("EGL: eglCreateContext failed (error=0x%x)\n", eglGetError());
        eglTerminate(task->eglDisplay);
        gbm_device_destroy(task->gbmDevice);
        close(task->gbmFd);
        task->gbmFd = -1;
        task->gbmDevice = NULL;
        task->eglDisplay = EGL_NO_DISPLAY;
        continue;
    }
    task->eglContext = ctx;

    /* Try creating a surface for eglMakeCurrent.
     * Order: pbuffer → GBM window surface → surfaceless.
     * NVIDIA requires a real window surface (pbuffer/surfaceless don't work). */
    EGLint pbufAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    task->eglSurface = eglCreatePbufferSurface(task->eglDisplay, config, pbufAttribs);
    if (task->eglSurface == EGL_NO_SURFACE) {
        DBG("EGL: pbuffer creation failed, trying GBM window surface\n");
        /* Create a GBM surface and use eglCreateWindowSurface — this is what
         * Wayland compositors and Firefox do on NVIDIA. */
        struct gbm_surface *gbmSurface = gbm_surface_create(task->gbmDevice,
            16, 16, GBM_FORMAT_ARGB8888, GBM_BO_USE_RENDERING);
        if (gbmSurface) {
            task->eglSurface = eglCreateWindowSurface(task->eglDisplay, config,
                (EGLNativeWindowType)gbmSurface, NULL);
            if (task->eglSurface != EGL_NO_SURFACE) {
                DBG("EGL: GBM window surface created OK\n");
            } else {
                DBG("EGL: GBM window surface failed (err=0x%x), will try surfaceless\n", eglGetError());
                gbm_surface_destroy(gbmSurface);
                task->eglSurface = EGL_NO_SURFACE;
            }
        } else {
            DBG("EGL: gbm_surface_create failed\n");
            task->eglSurface = EGL_NO_SURFACE;
        }
    }

    /* Verify eglMakeCurrent works NOW (same thread) — catch NVIDIA issues early */
    EGLSurface testSurf = (task->eglSurface != EGL_NO_SURFACE) ? task->eglSurface : EGL_NO_SURFACE;
    if (!eglMakeCurrent(task->eglDisplay, testSurf, testSurf, task->eglContext)) {
        EGLint err = eglGetError();
        DBG("EGL: GBM eglMakeCurrent failed with %s (err=0x%x)\n", useGLES ? "GLES" : "GL", err);

        /* If we were using GL, retry with GLES — NVIDIA proprietary EGL
         * doesn't support Desktop GL with pbuffer/surfaceless contexts. */
        if (!useGLES) {
            DBG("EGL: retrying GBM with GLES API\n");
            eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroyContext(task->eglDisplay, task->eglContext);
            if (task->eglSurface != EGL_NO_SURFACE) eglDestroySurface(task->eglDisplay, task->eglSurface);
            task->eglContext = EGL_NO_CONTEXT;
            task->eglSurface = EGL_NO_SURFACE;

            eglBindAPI(EGL_OPENGL_ES_API);
            EGLint glesCfg[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_NONE
            };
            EGLConfig glesCfgResult;
            EGLint glesN;
            if (eglChooseConfig(task->eglDisplay, glesCfg, &glesCfgResult, 1, &glesN) && glesN > 0) {
                EGLint glesCtx[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
                task->eglContext = eglCreateContext(task->eglDisplay, glesCfgResult, EGL_NO_CONTEXT, glesCtx);
                if (task->eglContext != EGL_NO_CONTEXT) {
                    EGLint pb[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
                    task->eglSurface = eglCreatePbufferSurface(task->eglDisplay, glesCfgResult, pb);
                    if (task->eglSurface == EGL_NO_SURFACE) task->eglSurface = EGL_NO_SURFACE;
                    EGLSurface s = (task->eglSurface != EGL_NO_SURFACE) ? task->eglSurface : EGL_NO_SURFACE;
                    if (eglMakeCurrent(task->eglDisplay, s, s, task->eglContext)) {
                        DBG("EGL: GBM GLES eglMakeCurrent OK!\n");
                        eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                        DBG("EGL: GBM context created successfully (surface=%s, api=GLES)\n",
                            task->eglSurface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless");
                        return 1;
                    }
                    DBG("EGL: GBM GLES eglMakeCurrent also failed (err=0x%x)\n", eglGetError());
                    eglDestroyContext(task->eglDisplay, task->eglContext);
                    if (task->eglSurface != EGL_NO_SURFACE) eglDestroySurface(task->eglDisplay, task->eglSurface);
                    task->eglContext = EGL_NO_CONTEXT;
                    task->eglSurface = EGL_NO_SURFACE;
                }
            }
        }

        /* All attempts on this node failed — try fallbacks */
        DBG("EGL: GBM all API attempts failed, trying fallbacks\n");
        eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (task->eglContext != EGL_NO_CONTEXT) eglDestroyContext(task->eglDisplay, task->eglContext);
        if (task->eglSurface != EGL_NO_SURFACE) eglDestroySurface(task->eglDisplay, task->eglSurface);
        eglTerminate(task->eglDisplay);
        gbm_device_destroy(task->gbmDevice);
        /* Keep gbmFd open for DRM params */
        task->eglDisplay = EGL_NO_DISPLAY;
        task->eglContext = EGL_NO_CONTEXT;
        task->eglSurface = EGL_NO_SURFACE;
        task->gbmDevice = NULL;

        if (initEGL_NvidiaVendor(task)) {
            return 1;
        }
        if (initEGL_SharedContext(task)) {
            return 1;
        }
        if (initEGL_DevicePlatform(task)) {
            return 1;
        }
        /* All fallbacks failed for this node — try next */
        close(task->gbmFd);
        task->gbmFd = -1;
        continue;
    }
    /* Success — unbind for now, render thread will re-bind */
    eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    DBG("EGL: GBM context created successfully (surface=%s, api=%s)\n",
        task->eglSurface != EGL_NO_SURFACE ? "pbuffer" : "surfaceless",
        useGLES ? "GLES" : "GL");
    return 1;

    } /* end for nodeIdx */

    DBG("EGL: all render nodes exhausted\n");
    return 0;
}

static void ensureFBO(CreateTask *task, int w, int h) {
    if (task->fboW == w && task->fboH == h && task->fbo != 0) return;

    if (task->fbo) {
        glDeleteFramebuffers(1, &task->fbo);
        glDeleteTextures(1, &task->fboTex);
    }

    glGenTextures(1, &task->fboTex);
    glBindTexture(GL_TEXTURE_2D, task->fboTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glGenFramebuffers(1, &task->fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, task->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, task->fboTex, 0);

    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        DBG("EGL: FBO incomplete (status=0x%x)\n", status);
    }

    task->fboW = w;
    task->fboH = h;
    DBG("EGL: FBO resized to %dx%d\n", w, h);
}

static void destroyEGL(CreateTask *task) {
    /* INTENTIONAL LEAK: Mesa Gallium shares pipe_screen across EGLDisplays.
     * Calling eglTerminate corrupts Compose/Skia. Resources reclaimed on exit. */
    task->eglDisplay = EGL_NO_DISPLAY;
    task->eglContext = EGL_NO_CONTEXT;
    task->fbo = 0;
    task->fboTex = 0;
}

static void *glGetProcAddressWrapper(void *ctx, const char *name) {
    (void)ctx;
    return (void *)eglGetProcAddress(name);
}

/* ------------------------------------------------------------------ */
/*  GL offscreen render                                                */
/* ------------------------------------------------------------------ */
static void renderFrameGL(CreateTask *task) {
    if (!task->renderCtx) return;

    int flags = mpv_render_context_update(task->renderCtx);
    if (!(flags & MPV_RENDER_UPDATE_FRAME)) return;

    int w = task->targetW;
    int h = task->targetH;
    if (w <= 0 || h <= 0) {
        int64_t vw = 0, vh = 0;
        mpv_get_property(task->mpv, "dwidth", MPV_FORMAT_INT64, &vw);
        mpv_get_property(task->mpv, "dheight", MPV_FORMAT_INT64, &vh);
        if (vw <= 0 || vh <= 0) return;
        w = (int)vw;
        h = (int)vh;
    }

    if (!eglMakeCurrent(task->eglDisplay, task->eglSurface, task->eglSurface, task->eglContext)) {
        DBG("renderFrameGL: eglMakeCurrent failed (error=0x%x)\n", eglGetError());
        return;
    }
    ensureFBO(task, w, h);

    mpv_opengl_fbo fbo_params = {
        .fbo = (int)task->fbo,
        .w = w,
        .h = h,
        .internal_format = 0
    };
    int flip_y = 0;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo_params},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {0}
    };

    if (mpv_render_context_render(task->renderCtx, params) < 0) {
        DBG("renderFrameGL: mpv_render_context_render failed\n");
        return;
    }

    pthread_mutex_lock(&task->frameMutex);
    int stride = w * 4;
    if (w != task->frameW || h != task->frameH) {
        char *newBuf = realloc(task->frameData, (size_t)stride * h);
        if (!newBuf) { pthread_mutex_unlock(&task->frameMutex); return; }
        task->frameData = newBuf;
        task->frameW = w;
        task->frameH = h;
        task->frameStride = stride;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, task->fbo);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, task->frameData);
    task->frameReady = 1;
    pthread_mutex_unlock(&task->frameMutex);
}

/* ------------------------------------------------------------------ */
/*  SW render (fallback if EGL init fails)                             */
/* ------------------------------------------------------------------ */
static void renderFrameSW(CreateTask *task) {
    if (!task->renderCtx) return;

    int flags = mpv_render_context_update(task->renderCtx);
    if (!(flags & MPV_RENDER_UPDATE_FRAME)) return;

    /* Always render at native video resolution — let Skia/Compose handle scaling.
     * This avoids mpv's expensive SW scaler on 4K content. */
    int64_t vw = 0, vh = 0;
    mpv_get_property(task->mpv, "dwidth", MPV_FORMAT_INT64, &vw);
    mpv_get_property(task->mpv, "dheight", MPV_FORMAT_INT64, &vh);
    if (vw <= 0 || vh <= 0) return;
    int w = (int)vw;
    int h = (int)vh;

    pthread_mutex_lock(&task->frameMutex);
    int stride = w * 4;
    if (w != task->frameW || h != task->frameH) {
        char *newBuf = realloc(task->frameData, (size_t)stride * h);
        if (!newBuf) { pthread_mutex_unlock(&task->frameMutex); return; }
        task->frameData = newBuf;
        task->frameW = w;
        task->frameH = h;
        task->frameStride = stride;
    }

    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SW_SIZE, &(int[2]){w, h}},
        {MPV_RENDER_PARAM_SW_FORMAT, (char *)"rgb0"},
        {MPV_RENDER_PARAM_SW_STRIDE, &stride},
        {MPV_RENDER_PARAM_SW_POINTER, task->frameData},
        {0}
    };
    if (mpv_render_context_render(task->renderCtx, params) < 0) {
        DBG("renderFrameSW: mpv_render_context_render failed\n");
    }
    task->frameReady = 1;
    pthread_mutex_unlock(&task->frameMutex);
}

static void mpvWakeupCallback(void *data) {
    CreateTask *task = (CreateTask *)data;
    task->frameSignal = 1;
    pthread_cond_signal(&task->frameCond);
}

static void *renderThreadFunc(void *data) {
    CreateTask *task = (CreateTask *)data;

    /* If gpuMode==2, create GL render context here where we own the EGL context.
     * This allows mpv to see eglGetCurrentDisplay() and init VAAPI interop. */
    if (task->gpuMode == 2 && !task->renderCtx) {
        /* Init EGL on this thread if not cached (NVIDIA requires same-thread create+MakeCurrent) */
        if (task->eglDisplay == EGL_NO_DISPLAY) {
            if (!initEGL(task)) {
                DBG("render thread: initEGL FAILED, falling back to SW\n");
                task->gpuMode = 0;
                int adv = 1;
                mpv_render_param sw_params[] = {
                    {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
                    {MPV_RENDER_PARAM_ADVANCED_CONTROL, &adv},
                    {0}
                };
                mpv_render_context_create(&task->renderCtx, task->mpv, sw_params);
                if (task->renderCtx) {
                    mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
                    mpv_set_property_string(task->mpv, "hwdec", "auto-copy");
                }
                goto render_loop;
            }
        }
        EGLBoolean mkRes = eglMakeCurrent(task->eglDisplay, task->eglSurface, task->eglSurface, task->eglContext);
        DBG("render thread: eglMakeCurrent=%d (err=0x%x)\n", mkRes, mkRes ? 0 : eglGetError());
        if (mkRes) {
            const char *glVersion = (const char *)glGetString(GL_VERSION);
            const char *glRenderer = (const char *)glGetString(GL_RENDERER);
            DBG("render thread: GL=%s renderer=%s\n", glVersion ? glVersion : "null", glRenderer ? glRenderer : "null");
        } else {
            DBG("render thread: eglMakeCurrent FAILED, falling back to SW\n");
            task->gpuMode = 0;
            int adv = 1;
            mpv_render_param sw_params[] = {
                {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
                {MPV_RENDER_PARAM_ADVANCED_CONTROL, &adv},
                {0}
            };
            mpv_render_context_create(&task->renderCtx, task->mpv, sw_params);
            if (task->renderCtx) {
                mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
                mpv_set_property_string(task->mpv, "hwdec", "auto-copy");
            }
            goto render_loop;
        }
        DBG("render thread: creating mpv GL render context (gbmFd=%d)\n", task->gbmFd);


        mpv_opengl_init_params gl_init = {
            .get_proc_address = glGetProcAddressWrapper,
            .get_proc_address_ctx = NULL,
        };
        int advanced = 1;

        /* DRM params only when we have a valid GBM fd (not in Device Platform mode) */
        mpv_opengl_drm_params_v2 drm_params = {
            .fd = task->gbmFd,
            .crtc_id = -1,
            .connector_id = -1,
            .render_fd = task->gbmFd,
        };
        mpv_render_param render_params_drm[] = {
            {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
            {MPV_RENDER_PARAM_DRM_DISPLAY_V2, &drm_params},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
            {0}
        };
        mpv_render_param render_params_nodrm[] = {
            {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
            {0}
        };
        mpv_render_param *render_params = (task->gbmFd > 0)
            ? render_params_drm : render_params_nodrm;

        if (mpv_render_context_create(&task->renderCtx, task->mpv, render_params) < 0) {
            /* If DRM params failed, retry without them */
            if (render_params == render_params_drm) {
                DBG("render thread: GL context with DRM failed, retrying without DRM\n");
                if (mpv_render_context_create(&task->renderCtx, task->mpv, render_params_nodrm) < 0) {
                    DBG("render thread: GL mpv_render_context_create FAILED (both paths)\n");
                    goto gl_create_failed;
                }
                mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
                DBG("render thread: GL render context created (no DRM)\n");
            } else {
                DBG("render thread: GL mpv_render_context_create FAILED\n");
                goto gl_create_failed;
            }
        } else {
            mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
            DBG("render thread: GL render context created successfully (VAAPI should work)\n");
        }
        goto render_loop;

gl_create_failed:
            eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            task->gpuMode = 0; /* fallback to SW */
            {
            int adv = 1;
            mpv_render_param sw_params[] = {
                {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
                {MPV_RENDER_PARAM_ADVANCED_CONTROL, &adv},
                {0}
            };
            mpv_render_context_create(&task->renderCtx, task->mpv, sw_params);
            if (task->renderCtx) {
                mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
                mpv_set_property_string(task->mpv, "hwdec", "auto-copy");
            }
            }
    } else if (task->gpuMode == 2 && task->renderCtx) {
        /* Cached path: render context already exists, just activate EGL */
        eglMakeCurrent(task->eglDisplay, task->eglSurface, task->eglSurface, task->eglContext);
        mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
        DBG("render thread: reusing cached GL context\n");
    }

render_loop:
    while (task->alive) {
        pthread_mutex_lock(&task->frameMutex);
        while (!task->frameSignal && task->alive) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 4000000;
            if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
            pthread_cond_timedwait(&task->frameCond, &task->frameMutex, &ts);
        }
        task->frameSignal = 0;
        pthread_mutex_unlock(&task->frameMutex);

        if (!task->alive) break;

        /* Process mpv events */
        if (task->mpv) {
            while (1) {
                mpv_event *event = mpv_wait_event(task->mpv, 0);
                if (event->event_id == MPV_EVENT_NONE) break;
            }
        }

        if (!task->alive) break;

        if (task->gpuMode == 2) {
            renderFrameGL(task);
        } else {
            renderFrameSW(task);
        }
    }

    /* Unbind EGL context from render thread */
    if (task->gpuMode == 2 && task->eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(task->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

    return NULL;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void)reserved;
    setlocale(LC_NUMERIC, "C");
    return JNI_VERSION_1_6;
}


/* ---- simple growable string buffer ---- */
typedef struct {
    char *s;
    size_t len;
    size_t cap;
} JsonBuf;

static void jb_init(JsonBuf *b) { b->s = NULL; b->len = 0; b->cap = 0; }

static void jb_grow(JsonBuf *b, size_t need) {
    if (b->len + need + 1 <= b->cap) return;
    size_t newCap = b->cap ? b->cap : 64;
    while (b->len + need + 1 > newCap) newCap *= 2;
    char *p = realloc(b->s, newCap);
    if (!p) return;
    b->s = p;
    b->cap = newCap;
}

static void jb_append(JsonBuf *b, const char *str) {
    if (!str) return;
    size_t n = strlen(str);
    jb_grow(b, n);
    memcpy(b->s + b->len, str, n);
    b->len += n;
    b->s[b->len] = '\0';
}

static void jb_append_c(JsonBuf *b, char c) {
    jb_grow(b, 1);
    b->s[b->len++] = c;
    b->s[b->len] = '\0';
}

__attribute__((__format__ (__printf__, 2, 3)))
static void jb_append_f(JsonBuf *b, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    jb_grow(b, (size_t)n);
    va_start(ap, fmt);
    vsnprintf(b->s + b->len, (size_t)n + 1, fmt, ap);
    va_end(ap);
    b->len += (size_t)n;
}

static void jb_escape(JsonBuf *b, const char *str) {
    if (!str) { jb_append(b, "null"); return; }
    jb_append_c(b, '"');
    for (const unsigned char *p = (const unsigned char *)str; *p; p++) {
        switch (*p) {
            case '\\': jb_append(b, "\\\\"); break;
            case '"':  jb_append(b, "\\\""); break;
            case '\n': jb_append(b, "\\n"); break;
            case '\r': jb_append(b, "\\r"); break;
            case '\t': jb_append(b, "\\t"); break;
            default:
                if (*p < 0x20) { char buf[8]; snprintf(buf, sizeof buf, "\\u%04x", *p); jb_append(b, buf); }
                else jb_append_c(b, (char)*p);
        }
    }
    jb_append_c(b, '"');
}

static char *tracks_json_for_type(mpv_handle *mpv, const char *type) {
    mpv_node tracks;
    if (mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &tracks) < 0)
        return strdup("[]");
    JsonBuf b; jb_init(&b);
    jb_append_c(&b, '[');
    if (tracks.format == MPV_FORMAT_NODE_ARRAY && tracks.u.list) {
        int first = 1;
        for (int i = 0; i < tracks.u.list->num; i++) {
            mpv_node *node = &tracks.u.list->values[i];
            if (node->format != MPV_FORMAT_NODE_MAP || !node->u.list) continue;
            const char *node_type = NULL;
            int track_id = 0, selected = 0;
            const char *lang = NULL, *label = NULL;
            for (int j = 0; j < node->u.list->num; j++) {
                const char *key = node->u.list->keys[j];
                mpv_node *val = &node->u.list->values[j];
                if (strcmp(key, "type") == 0 && val->format == MPV_FORMAT_STRING) node_type = val->u.string;
                else if (strcmp(key, "id") == 0 && val->format == MPV_FORMAT_INT64) track_id = (int)val->u.int64;
                else if (strcmp(key, "selected") == 0 && val->format == MPV_FORMAT_FLAG) selected = val->u.flag;
                else if (strcmp(key, "lang") == 0 && val->format == MPV_FORMAT_STRING) lang = val->u.string;
                else if (strcmp(key, "title") == 0 && val->format == MPV_FORMAT_STRING) label = val->u.string;
            }
            if (!node_type || strcmp(node_type, type) != 0) continue;
            if (!first) jb_append_c(&b, ',');
            first = 0;
            jb_append_c(&b, '{');
            jb_append_f(&b, "\"id\":\"%d\"", track_id);
            jb_append_f(&b, ",\"index\":%d", track_id - 1);
            jb_append(&b, ",\"label\":"); jb_escape(&b, label ? label : (lang ? lang : "Track"));
            jb_append(&b, ",\"language\":"); jb_escape(&b, lang ? lang : "");
            jb_append_f(&b, ",\"selected\":%s", selected ? "true" : "false");
            jb_append_c(&b, '}');
        }
    }
    jb_append_c(&b, ']');
    mpv_free_node_contents(&tracks);
    return b.s ? b.s : strdup("[]");
}


/* ------------------------------------------------------------------ */
/*  create / dispose                                                   */
/* ------------------------------------------------------------------ */
JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env, jobject thiz,
    jlong hostViewPtr,
    jstring sourceUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jstring controlsPageUrl,
    jint decoderPriority, jboolean nvidiaRtxSuperResolutionEnabled,
    jobject eventSink) {
    (void)thiz;
    (void)decoderPriority; (void)nvidiaRtxSuperResolutionEnabled;
    (void)hostViewPtr; /* gpuMode=1 (wid) removed — Linux always uses offscreen */

    DBG("create() called (offscreen GL/SW mode)\n");

    CreateTask *task = calloc(1, sizeof(CreateTask));
    if (!task) { DBG("create: calloc failed\n"); return 0; }

    (*env)->GetJavaVM(env, &task->jvm);
    task->alive = 1;
    task->gpuMode = 0;
    task->eglDisplay = EGL_NO_DISPLAY;

    /* Capture Skia/Compose's EGL display from JNI thread for shared context fallback.
     * On NVIDIA, creating a new display fails but reusing Skia's works. */
    task->skiaDisplay = eglGetCurrentDisplay();
    if (task->skiaDisplay == EGL_NO_DISPLAY) {
        /* Try default display — NVIDIA shares it process-wide */
        task->skiaDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }
    DBG("create: captured skiaDisplay=%p\n", (void*)task->skiaDisplay);

    pthread_mutex_init(&task->frameMutex, NULL);
    pthread_cond_init(&task->frameCond, NULL);

    if (eventSink) {
        task->eventSink = (*env)->NewGlobalRef(env, eventSink);
        jclass cls = (*env)->GetObjectClass(env, eventSink);
        task->eventMethod = (*env)->GetMethodID(env, cls, "onPlayerEvent", "(Ljava/lang/String;D)V");
        (*env)->DeleteLocalRef(env, cls);
    }

    {
        const char *src = sourceUrl ? (*env)->GetStringUTFChars(env, sourceUrl, NULL) : NULL;
        if (src) { task->sourceUrl = strdup(src); (*env)->ReleaseStringUTFChars(env, sourceUrl, src); }
    }
    (void)controlsPageUrl;

    jsize nh = headerLines ? (*env)->GetArrayLength(env, headerLines) : 0;
    task->nheaders = nh;
    if (nh > 0) {
        task->headers = calloc(nh, sizeof(char *));
        for (jsize i = 0; i < nh; i++) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, headerLines, i);
            const char *h = (*env)->GetStringUTFChars(env, s, NULL);
            if (h) { task->headers[i] = strdup(h); (*env)->ReleaseStringUTFChars(env, s, h); }
            (*env)->DeleteLocalRef(env, s);
        }
    }

    setlocale(LC_NUMERIC, "C");
    task->mpv = mpv_create();
    if (!task->mpv) {
        DBG("create: mpv_create failed\n");
        callEventSink(env, task->jvm, task->eventSink, task->eventMethod, "error", 5.0);
        free(task->sourceUrl);
        if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
        if (task->eventSink) (*env)->DeleteGlobalRef(env, task->eventSink);
        pthread_mutex_destroy(&task->frameMutex);
        free(task);
        return 0;
    }

    /* GL offscreen */
    pthread_mutex_lock(&glCacheMutex);
    if (glCache.valid) {
        DBG("create: reusing cached GL instance\n");
        task->gbmFd = glCache.gbmFd;
        task->gbmDevice = glCache.gbmDevice;
        task->eglDisplay = glCache.eglDisplay;
        task->eglContext = glCache.eglContext;
        task->fbo = glCache.fbo;
        task->fboTex = glCache.fboTex;
        task->fboW = glCache.fboW;
        task->fboH = glCache.fboH;
        if (glCache.mpv && glCache.renderCtx) {
            mpv_terminate_destroy(task->mpv);
            task->mpv = glCache.mpv;
            task->renderCtx = glCache.renderCtx;
        }
        glCache.valid = 0;
        glCache.mpv = NULL;
        glCache.renderCtx = NULL;
        pthread_mutex_unlock(&glCacheMutex);
        task->gpuMode = 2;
        DBG("create: cached GL instance restored\n");
    } else {
        pthread_mutex_unlock(&glCacheMutex);
        /* Defer EGL init to render thread — NVIDIA GBM/EGL doesn't support
         * context creation on one thread + MakeCurrent on another. */
        task->gpuMode = 2;
        mpv_set_option_string(task->mpv, "vo", "libmpv");
        mpv_set_option_string(task->mpv, "gpu-api", "opengl");
        DBG("create: offscreen GL mode, EGL deferred to render thread\n");
    }

    int reusingCachedMpv = (task->gpuMode == 2 && task->renderCtx != NULL);

    if (reusingCachedMpv) {
        DBG("create: reusing cached mpv, skipping init\n");
        if (task->nheaders > 0 && task->headers) {
            size_t len = 0;
            for (int i = 0; i < task->nheaders; i++)
                if (task->headers[i]) len += strlen(task->headers[i]) * 2 + 2;
            if (len > 0) {
                char *hdr = malloc(len + 1);
                hdr[0] = '\0';
                for (int i = 0; i < task->nheaders; i++) {
                    if (!task->headers[i]) continue;
                    if (hdr[0] != '\0') strcat(hdr, ",");
                    const char *src = task->headers[i];
                    char *dst = hdr + strlen(hdr);
                    while (*src) {
                        if (*src == '\\' || *src == ',') *dst++ = '\\';
                        *dst++ = *src++;
                    }
                    *dst = '\0';
                }
                mpv_set_property_string(task->mpv, "http-header-fields", hdr);
                free(hdr);
            }
        }
        mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
        /* Cached path: start render thread then jump to loadfile */
        pthread_create(&task->renderThread, NULL, renderThreadFunc, task);
        goto skip_init;
    }

    /* -- Common options -- */
    mpv_set_option_string(task->mpv, "cache", "yes");
    mpv_set_option_string(task->mpv, "cache-secs", "3");
    mpv_set_option_string(task->mpv, "demuxer-max-bytes", "50M");
    mpv_set_option_string(task->mpv, "demuxer-max-back-bytes", "25M");
    mpv_set_option_string(task->mpv, "audio-file-auto", "no");
    mpv_set_option_string(task->mpv, "sub-auto", "no");
    mpv_set_option_string(task->mpv, "config", "yes");
    mpv_set_option_string(task->mpv, "terminal", "no");
    mpv_set_option_string(task->mpv, "msg-level", "all=no");

    mpv_set_option_string(task->mpv, "hwdec", "auto-copy");

    mpv_set_option_string(task->mpv, "vd-lavc-dr", "yes");
    mpv_set_option_string(task->mpv, "video-latency-hacks", "yes");
    mpv_set_option_string(task->mpv, "keep-open", "no");

    if (task->nheaders > 0 && task->headers) {
        /* mpv parses http-header-fields as comma-separated list.
         * Escape backslashes and commas in header values to prevent splitting. */
        size_t len = 0;
        for (int i = 0; i < task->nheaders; i++)
            if (task->headers[i]) len += strlen(task->headers[i]) * 2 + 2;
        if (len > 0) {
            char *hdr = malloc(len + 1);
            hdr[0] = '\0';
            for (int i = 0; i < task->nheaders; i++) {
                if (!task->headers[i]) continue;
                if (hdr[0] != '\0') strcat(hdr, ",");
                /* Escape \ and , in each header line */
                const char *src = task->headers[i];
                char *dst = hdr + strlen(hdr);
                while (*src) {
                    if (*src == '\\' || *src == ',') *dst++ = '\\';
                    *dst++ = *src++;
                }
                *dst = '\0';
            }
            mpv_set_option_string(task->mpv, "http-header-fields", hdr);
            free(hdr);
        }
    }

    if (mpv_initialize(task->mpv) < 0) {
        DBG("create: mpv_initialize failed\n");
        mpv_terminate_destroy(task->mpv);
        callEventSink(env, task->jvm, task->eventSink, task->eventMethod, "error", 6.0);
        free(task->sourceUrl);
        if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
        if (task->eventSink) (*env)->DeleteGlobalRef(env, task->eventSink);
        pthread_mutex_destroy(&task->frameMutex);
        if (task->gpuMode == 2) destroyEGL(task);
        free(task);
        return 0;
    }

    /* -- Create render context -- */
    if (task->gpuMode == 2) {
        /* Create render context on a dedicated thread where we can own EGL.
         * JNI thread has Skia's EGL active so eglMakeCurrent fails here.
         * We spawn render thread early, let it create the mpv render context
         * with EGL active, then continue rendering. */
        DBG("create: deferring GL render context to render thread\n");
    }

    if (task->gpuMode == 0) {
        int advanced = 1;
        mpv_render_param render_params[] = {
            {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
            {0}
        };
        if (mpv_render_context_create(&task->renderCtx, task->mpv, render_params) < 0) {
            DBG("create: SW mpv_render_context_create failed\n");
            mpv_terminate_destroy(task->mpv);
            callEventSink(env, task->jvm, task->eventSink, task->eventMethod, "error", 7.0);
            free(task->sourceUrl);
            if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
            if (task->eventSink) (*env)->DeleteGlobalRef(env, task->eventSink);
            pthread_mutex_destroy(&task->frameMutex);
            free(task);
            return 0;
        }
        mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);
        mpv_set_property_string(task->mpv, "hwdec", "auto-copy");
        DBG("create: SW render context created (fallback)\n");
    }

    DBG("create: mpv initialized (gpuMode=%d)\n", task->gpuMode);

    /* Start render thread — for gpuMode==2 it creates the GL render context */
    pthread_create(&task->renderThread, NULL, renderThreadFunc, task);

    /* Wait for render thread to finish creating context (gpuMode==2) */
    if (task->gpuMode == 2) {
        /* Busy wait for renderCtx — render thread sets it quickly */
        for (int i = 0; i < 500 && !task->renderCtx && task->alive; i++) {
            usleep(2000); /* 2ms */
        }
        if (!task->renderCtx) {
            DBG("create: render thread failed to create context, aborting\n");
            task->alive = 0;
            pthread_join(task->renderThread, NULL);
            mpv_terminate_destroy(task->mpv);
            callEventSink(env, task->jvm, task->eventSink, task->eventMethod, "error", 8.0);
            free(task->sourceUrl);
            if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
            if (task->eventSink) (*env)->DeleteGlobalRef(env, task->eventSink);
            pthread_mutex_destroy(&task->frameMutex);
            free(task);
            return 0;
        }
    }

skip_init:
    if (task->sourceUrl) {
        if (initialPositionMs > 0) {
            char startOpt[80];
            snprintf(startOpt, sizeof(startOpt), "start=%f", (double)initialPositionMs / 1000.0);
            const char *cmd[] = {"loadfile", task->sourceUrl, "replace", "0", startOpt, NULL};
            mpv_command_async(task->mpv, 0, cmd);
        } else {
            const char *cmd[] = {"loadfile", task->sourceUrl, "replace", NULL};
            mpv_command_async(task->mpv, 0, cmd);
        }
    }

    if (!playWhenReady) {
        mpv_set_property_string(task->mpv, "pause", "yes");
    }

    DBG("create: returning handle (gpuMode=%d)\n", task->gpuMode);
    return (jlong)(intptr_t)task;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (!handle) return;
    CreateTask *task = (CreateTask *)(intptr_t)handle;
    DBG("dispose() called (gpuMode=%d)\n", task->gpuMode);
    task->alive = 0;
    pthread_cond_signal(&task->frameCond);

    if (task->gpuMode == 2) {
        if (task->mpv) {
            const char *cmd[] = {"stop", NULL};
            mpv_command(task->mpv, cmd);
            mpv_wakeup(task->mpv);
        }
        if (task->renderThread) pthread_join(task->renderThread, NULL);

        /* Cache for reuse — do NOT free mpv/renderCtx (crashes Gallium) */
        pthread_mutex_lock(&glCacheMutex);
        glCache.valid = 1;
        glCache.gbmFd = task->gbmFd;
        glCache.gbmDevice = task->gbmDevice;
        glCache.eglDisplay = task->eglDisplay;
        glCache.eglContext = task->eglContext;
        glCache.mpv = task->mpv;
        glCache.renderCtx = task->renderCtx;
        glCache.fbo = task->fbo;
        glCache.fboTex = task->fboTex;
        glCache.fboW = task->fboW;
        glCache.fboH = task->fboH;
        pthread_mutex_unlock(&glCacheMutex);

        task->renderCtx = NULL;
        task->mpv = NULL;
        task->eglDisplay = EGL_NO_DISPLAY;
        task->eglContext = EGL_NO_CONTEXT;
        DBG("dispose: GL instance cached for reuse\n");
    } else {
        if (task->mpv) mpv_wakeup(task->mpv);
        if (task->renderThread) pthread_join(task->renderThread, NULL);
        if (task->renderCtx) { mpv_render_context_free(task->renderCtx); task->renderCtx = NULL; }
    }

    if (task->mpv) { mpv_terminate_destroy(task->mpv); task->mpv = NULL; }
    if (task->gpuMode == 2) destroyEGL(task);

    if (task->eventSink && task->jvm) {
        JavaVM *jvm = task->jvm;
        JNIEnv *e = NULL; int detach = 0;
        jint ret = (*jvm)->GetEnv(jvm, (void**)&e, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) { (*jvm)->AttachCurrentThread(jvm, (void**)&e, NULL); detach = 1; }
        if (e) (*e)->DeleteGlobalRef(e, task->eventSink);
        if (detach) (*jvm)->DetachCurrentThread(jvm);
    }
    task->eventSink = NULL;

    pthread_mutex_lock(&task->frameMutex);
    free(task->frameData); task->frameData = NULL;
    pthread_mutex_unlock(&task->frameMutex);

    free(task->sourceUrl);
    if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
}


/* ------------------------------------------------------------------ */
/*  JNI property/control functions                                     */
/* ------------------------------------------------------------------ */
static CreateTask *getTask(jlong handle) {
    return handle ? (CreateTask *)(intptr_t)handle : NULL;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(JNIEnv *env, jobject thiz, jlong handle, jstring json) {
    (void)env; (void)thiz; (void)handle; (void)json;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(JNIEnv *env, jobject thiz, jlong hdl, jboolean paused) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    mpv_set_property_string(task->mpv, "pause", paused ? "yes" : "no");
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(JNIEnv *env, jobject thiz, jlong hdl, jlong posMs) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    char buf[64]; snprintf(buf, sizeof(buf), "%f", (double)posMs / 1000.0);
    mpv_set_property_string(task->mpv, "time-pos", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(JNIEnv *env, jobject thiz, jlong hdl, jlong offMs) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    char buf[32]; snprintf(buf, sizeof(buf), "%ld", (long)(offMs / 1000));
    const char *cmd[] = {"seek", buf, "relative", NULL};
    mpv_command_async(task->mpv, 0, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(JNIEnv *env, jobject thiz, jlong hdl, jfloat speed) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    char buf[32]; snprintf(buf, sizeof(buf), "%f", (double)speed);
    mpv_set_property_string(task->mpv, "speed", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_adjustVolume(JNIEnv *env, jobject thiz, jlong hdl, jfloat delta) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    double vol = 100.0; mpv_get_property(task->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    vol += (double)delta; if (vol < 0) vol = 0; if (vol > 200) vol = 200;
    char buf[32]; snprintf(buf, sizeof(buf), "%f", vol);
    mpv_set_property_string(task->mpv, "volume", buf);
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0.0f;
    double vol = 100.0; mpv_get_property(task->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    return (jfloat)(vol / 100.0);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(JNIEnv *env, jobject thiz, jlong hdl, jfloat level) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    double vol = (double)level * 100.0; if (vol < 0) vol = 0; if (vol > 200) vol = 200;
    char buf[32]; snprintf(buf, sizeof(buf), "%f", vol);
    mpv_set_property_string(task->mpv, "volume", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(JNIEnv *env, jobject thiz, jlong hdl, jint mode) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    mpv_set_property_string(task->mpv, "panscan", mode == 1 ? "1.0" : "0.0");
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0;
    double d = 0; mpv_get_property(task->mpv, "duration", MPV_FORMAT_DOUBLE, &d);
    return (jlong)(d * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0;
    double pos = 0; mpv_get_property(task->mpv, "time-pos", MPV_FORMAT_DOUBLE, &pos);
    return (jlong)(pos * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0;
    double pos = 0, cache = 0;
    mpv_get_property(task->mpv, "time-pos", MPV_FORMAT_DOUBLE, &pos);
    mpv_get_property(task->mpv, "demuxer-cache-duration", MPV_FORMAT_DOUBLE, &cache);
    return (jlong)((pos + cache) * 1000.0);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return JNI_FALSE;
    int v = 0; mpv_get_property(task->mpv, "paused-for-cache", MPV_FORMAT_FLAG, &v);
    if (v) return JNI_TRUE;
    mpv_get_property(task->mpv, "seeking", MPV_FORMAT_FLAG, &v);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return JNI_FALSE;
    int v = 0; mpv_get_property(task->mpv, "eof-reached", MPV_FORMAT_FLAG, &v);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return JNI_TRUE;
    int v = 0; mpv_get_property(task->mpv, "pause", MPV_FORMAT_FLAG, &v);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 1.0f;
    double v = 1.0; mpv_get_property(task->mpv, "speed", MPV_FORMAT_DOUBLE, &v);
    return (jfloat)v;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)thiz; CreateTask *task = getTask(hdl);
    if (!task || !task->mpv) return (*env)->NewStringUTF(env, "[]");
    char *json = tracks_json_for_type(task->mpv, "audio");
    jstring r = (*env)->NewStringUTF(env, json ? json : "[]"); free(json); return r;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)thiz; CreateTask *task = getTask(hdl);
    if (!task || !task->mpv) return (*env)->NewStringUTF(env, "[]");
    char *json = tracks_json_for_type(task->mpv, "sub");
    jstring r = (*env)->NewStringUTF(env, json ? json : "[]"); free(json); return r;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    char buf[16]; snprintf(buf, sizeof(buf), "%d", id);
    mpv_set_property_string(task->mpv, "aid", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    if (id <= 0) { mpv_set_property_string(task->mpv, "sid", "no"); return; }
    char buf[16]; snprintf(buf, sizeof(buf), "%d", id);
    mpv_set_property_string(task->mpv, "sid", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(JNIEnv *env, jobject thiz, jlong hdl, jstring url) {
    (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv || !url) return;
    const char *u = (*env)->GetStringUTFChars(env, url, NULL);
    if (u) { const char *cmd[] = {"sub-add", u, "auto", NULL}; mpv_command_async(task->mpv, 0, cmd); (*env)->ReleaseStringUTFChars(env, url, u); }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    mpv_set_property_string(task->mpv, "sid", "no");
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    if (id <= 0) { mpv_set_property_string(task->mpv, "sid", "no"); return; }
    char buf[16]; snprintf(buf, sizeof(buf), "%d", id); mpv_set_property_string(task->mpv, "sid", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applyWindowChrome(JNIEnv *env, jobject thiz, jlong hwnd, jboolean dm, jint cc, jint bc, jint tc) {
    (void)env; (void)thiz; (void)hwnd; (void)dm; (void)cc; (void)bc; (void)tc;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setWindowBorderlessFullscreen(JNIEnv *env, jobject thiz, jlong hwnd, jboolean fs, jint x, jint y, jint w, jint h) {
    (void)env; (void)thiz; (void)hwnd; (void)fs; (void)x; (void)y; (void)w; (void)h;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(JNIEnv *env, jobject thiz, jlong hdl, jint delayMs) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    char buf[32]; snprintf(buf, sizeof(buf), "%f", (double)delayMs / 1000.0);
    mpv_set_property_string(task->mpv, "sub-delay", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env, jobject thiz, jlong hdl,
    jstring textColor, jstring backgroundColor, jstring outlineColor,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos) {
    (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return;
    mpv_set_property_string(task->mpv, "sub-ass-override", "yes");
    const char *s;
    if (textColor) { s = (*env)->GetStringUTFChars(env, textColor, NULL); if (s) { mpv_set_property_string(task->mpv, "sub-color", s); (*env)->ReleaseStringUTFChars(env, textColor, s); } }
    if (backgroundColor) { s = (*env)->GetStringUTFChars(env, backgroundColor, NULL); if (s) { mpv_set_property_string(task->mpv, "sub-back-color", s); (*env)->ReleaseStringUTFChars(env, backgroundColor, s); } }
    if (outlineColor) { s = (*env)->GetStringUTFChars(env, outlineColor, NULL); if (s) { mpv_set_property_string(task->mpv, "sub-border-color", s); (*env)->ReleaseStringUTFChars(env, outlineColor, s); } }
    char buf[32];
    snprintf(buf, sizeof(buf), "%f", (double)outlineSize); mpv_set_property_string(task->mpv, "sub-border-size", buf);
    mpv_set_property_string(task->mpv, "sub-bold", bold ? "yes" : "no");
    snprintf(buf, sizeof(buf), "%f", (double)fontSize); mpv_set_property_string(task->mpv, "sub-font-size", buf);
    snprintf(buf, sizeof(buf), "%d", subPos); mpv_set_property_string(task->mpv, "sub-pos", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setProperty(JNIEnv *env, jobject thiz, jlong hdl, jstring name, jstring value) {
    (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv || !name || !value) return;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    if (n && v) mpv_set_property_string(task->mpv, n, v);
    if (n) (*env)->ReleaseStringUTFChars(env, name, n);
    if (v) (*env)->ReleaseStringUTFChars(env, value, v);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_warmupWebView2(JNIEnv *env, jobject thiz, jstring url) { (void)env; (void)thiz; (void)url; return JNI_FALSE; }
JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_shutdownWebView2Warmup(JNIEnv *env, jobject thiz) { (void)env; (void)thiz; }

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_resizeNativeView(JNIEnv *env, jobject thiz, jlong hdl, jint w, jint h) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task) return;
    task->targetW = w; task->targetH = h;
}

JNIEXPORT jint JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_videoWidth(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0;
    int64_t w = 0; mpv_get_property(task->mpv, "dwidth", MPV_FORMAT_INT64, &w);
    return (jint)w;
}

JNIEXPORT jint JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_videoHeight(JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz; CreateTask *task = getTask(hdl); if (!task || !task->mpv) return 0;
    int64_t h = 0; mpv_get_property(task->mpv, "dheight", MPV_FORMAT_INT64, &h);
    return (jint)h;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_renderFrame(
    JNIEnv *env, jobject thiz, jlong handle,
    jintArray dstPixels, jint dstW, jint dstH) {
    (void)thiz;
    CreateTask *task = getTask(handle);
    if (!task || !task->alive) return JNI_FALSE;

    task->targetW = dstW;
    task->targetH = dstH;

    pthread_mutex_lock(&task->frameMutex);
    if (!task->frameData || !task->alive) {
        pthread_mutex_unlock(&task->frameMutex);
        return JNI_FALSE;
    }

    int srcW = task->frameW;
    int srcH = task->frameH;
    int srcStride = task->frameStride;
    char *src = task->frameData;

    if (!task->frameReady || srcW <= 0 || srcH <= 0) {
        pthread_mutex_unlock(&task->frameMutex);
        return JNI_FALSE;
    }
    task->frameReady = 0;

    jint *dst = (*env)->GetIntArrayElements(env, dstPixels, NULL);
    if (!dst) { pthread_mutex_unlock(&task->frameMutex); return JNI_FALSE; }

    if (srcW == dstW && srcH == dstH) {
        for (int y = 0; y < srcH; y++) {
            unsigned char *row = (unsigned char *)(src + y * srcStride);
            jint *dstRow = dst + y * dstW;
            for (int x = 0; x < srcW; x++) {
                unsigned char r = row[x * 4 + 0];
                unsigned char g = row[x * 4 + 1];
                unsigned char b = row[x * 4 + 2];
                dstRow[x] = (jint)((0xFFu << 24) | ((unsigned)r << 16) | ((unsigned)g << 8) | b);
            }
        }
    } else {
        /* Letterbox scaling */
        double aspect = (double)srcW / srcH;
        double dstAspect = (double)dstW / dstH;
        int drawW, drawH, offX, offY;
        if (dstAspect > aspect) { drawH = dstH; drawW = (int)(dstH * aspect + 0.5); offX = (dstW - drawW) / 2; offY = 0; }
        else { drawW = dstW; drawH = (int)(dstW / aspect + 0.5); offX = 0; offY = (dstH - drawH) / 2; }
        if (drawW > dstW) drawW = dstW; if (drawH > dstH) drawH = dstH;
        for (int y = 0; y < drawH; y++) {
            int srcY = y * srcH / drawH;
            unsigned char *row = (unsigned char *)(src + srcY * srcStride);
            for (int x = 0; x < drawW; x++) {
                int srcX = x * srcW / drawW;
                unsigned char r = row[srcX * 4 + 0], g = row[srcX * 4 + 1], b = row[srcX * 4 + 2];
                dst[(y + offY) * dstW + (x + offX)] = (jint)((0xFFu << 24) | ((unsigned)r << 16) | ((unsigned)g << 8) | b);
            }
        }
    }

    (*env)->ReleaseIntArrayElements(env, dstPixels, dst, 0);
    pthread_mutex_unlock(&task->frameMutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_renderFrameBytes(
    JNIEnv *env, jobject thiz, jlong handle,
    jbyteArray dstBytes, jint dstW, jint dstH) {
    (void)thiz;
    CreateTask *task = getTask(handle);
    if (!task || !task->alive) return JNI_FALSE;

    task->targetW = dstW;
    task->targetH = dstH;

    pthread_mutex_lock(&task->frameMutex);
    if (!task->frameData || !task->alive) {
        pthread_mutex_unlock(&task->frameMutex);
        return JNI_FALSE;
    }

    int srcW = task->frameW;
    int srcH = task->frameH;
    int srcStride = task->frameStride;
    char *src = task->frameData;

    if (!task->frameReady || srcW <= 0 || srcH <= 0) {
        pthread_mutex_unlock(&task->frameMutex);
        return JNI_FALSE;
    }
    task->frameReady = 0;

    jbyte *dst = (*env)->GetByteArrayElements(env, dstBytes, NULL);
    if (!dst) { pthread_mutex_unlock(&task->frameMutex); return JNI_FALSE; }

    jsize dstLen = (*env)->GetArrayLength(env, dstBytes);
    if (dstLen < (jsize)(dstW * dstH * 4)) {
        (*env)->ReleaseByteArrayElements(env, dstBytes, dst, JNI_ABORT);
        pthread_mutex_unlock(&task->frameMutex);
        return JNI_FALSE;
    }

    if (srcW == dstW && srcH == dstH) {
        /* RGBA -> BGRA (Skia N32 on little-endian) */
        for (int y = 0; y < srcH; y++) {
            unsigned char *sRow = (unsigned char *)(src + y * srcStride);
            unsigned char *dRow = (unsigned char *)(dst + y * dstW * 4);
            for (int x = 0; x < srcW; x++) {
                dRow[x * 4 + 0] = sRow[x * 4 + 2]; /* B */
                dRow[x * 4 + 1] = sRow[x * 4 + 1]; /* G */
                dRow[x * 4 + 2] = sRow[x * 4 + 0]; /* R */
                dRow[x * 4 + 3] = 0xFF;             /* A */
            }
        }
    } else {
        double aspect = (double)srcW / srcH;
        double dstAspect = (double)dstW / dstH;
        int drawW, drawH, offX, offY;
        if (dstAspect > aspect) { drawH = dstH; drawW = (int)(dstH * aspect + 0.5); offX = (dstW - drawW) / 2; offY = 0; }
        else { drawW = dstW; drawH = (int)(dstW / aspect + 0.5); offX = 0; offY = (dstH - drawH) / 2; }
        if (drawW > dstW) drawW = dstW; if (drawH > dstH) drawH = dstH;
        memset(dst, 0, (size_t)dstW * dstH * 4);
        for (int y = 0; y < drawH; y++) {
            int srcY = y * srcH / drawH;
            unsigned char *sRow = (unsigned char *)(src + srcY * srcStride);
            unsigned char *dRow = (unsigned char *)(dst + ((y + offY) * dstW + offX) * 4);
            for (int x = 0; x < drawW; x++) {
                int srcX = x * srcW / drawW;
                dRow[x * 4 + 0] = sRow[srcX * 4 + 2];
                dRow[x * 4 + 1] = sRow[srcX * 4 + 1];
                dRow[x * 4 + 2] = sRow[srcX * 4 + 0];
                dRow[x * 4 + 3] = 0xFF;
            }
        }
    }

    (*env)->ReleaseByteArrayElements(env, dstBytes, dst, 0);
    pthread_mutex_unlock(&task->frameMutex);
    return JNI_TRUE;
}
