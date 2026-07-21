#define _GNU_SOURCE
#include <jni.h>
#include <mpv/client.h>
#include <mpv/render_gl.h>
#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>
#include <X11/Xlib.h>
#include <GL/glx.h>
#include <EGL/egl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <locale.h>
#include <unistd.h>
#include <stdint.h>
#include <math.h>
#include <limits.h>
#include <errno.h>
#include <time.h>

static pthread_once_t gGtkThreadOnce = PTHREAD_ONCE_INIT;

static void *gtkThreadMain(void *arg) {
    (void)arg;
    setenv("GDK_BACKEND", "x11", 1);
    setenv("GDK_DEBUG", "no-vsync", 1);
    int argc = 0;
    gtk_init(&argc, NULL);
    gtk_main();
    return NULL;
}

static void startGtkThreadOnce(void) {
    XInitThreads();
    pthread_t thread;
    pthread_create(&thread, NULL, gtkThreadMain, NULL);
    pthread_detach(thread);
    while (!gtk_init_check(NULL, NULL) && gdk_display_get_default() == NULL) {
        usleep(2000);
    }
}

static void ensureGtkThread(void) {
    pthread_once(&gGtkThreadOnce, startGtkThreadOnce);
    while (gdk_display_get_default() == NULL) {
        usleep(2000);
    }
}

typedef struct {
    void (*fn)(void *arg);
    void *arg;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int done;
    int abandoned;
} GtkSyncTask;

static gboolean gtkSyncTaskTrampoline(gpointer data) {
    GtkSyncTask *task = (GtkSyncTask *)data;
    pthread_mutex_lock(&task->mutex);
    if (task->abandoned) {
        pthread_mutex_unlock(&task->mutex);
        pthread_mutex_destroy(&task->mutex);
        pthread_cond_destroy(&task->cond);
        free(task);
        return G_SOURCE_REMOVE;
    }
    pthread_mutex_unlock(&task->mutex);

    task->fn(task->arg);

    pthread_mutex_lock(&task->mutex);
    task->done = 1;
    pthread_cond_signal(&task->cond);
    pthread_mutex_unlock(&task->mutex);
    return G_SOURCE_REMOVE;
}

static int runOnGtkThreadSyncTimeout(void (*fn)(void *arg), void *arg, int timeoutMs) {
    GtkSyncTask *task = malloc(sizeof(GtkSyncTask));
    task->fn = fn;
    task->arg = arg;
    task->done = 0;
    task->abandoned = 0;
    pthread_mutex_init(&task->mutex, NULL);
    pthread_cond_init(&task->cond, NULL);
    g_idle_add(gtkSyncTaskTrampoline, task);

    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeoutMs / 1000;
    ts.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }

    pthread_mutex_lock(&task->mutex);
    int result = 0;
    while (!task->done) {
        int rc = pthread_cond_timedwait(&task->cond, &task->mutex, &ts);
        if (rc == ETIMEDOUT) break;
    }
    result = task->done;
    if (!result) task->abandoned = 1;
    pthread_mutex_unlock(&task->mutex);

    if (result) {
        pthread_mutex_destroy(&task->mutex);
        pthread_cond_destroy(&task->cond);
        free(task);
    }
    return result;
}

static void runOnGtkThreadSync(void (*fn)(void *arg), void *arg) {
    runOnGtkThreadSyncTimeout(fn, arg, 10000);
}

typedef struct {
    void (*fn)(void *arg);
    void *arg;
} GtkAsyncTask;

static gboolean gtkAsyncTaskTrampoline(gpointer data) {
    GtkAsyncTask *task = (GtkAsyncTask *)data;
    task->fn(task->arg);
    free(task);
    return G_SOURCE_REMOVE;
}

static void runOnGtkThreadAsync(void (*fn)(void *arg), void *arg) {
    GtkAsyncTask *task = malloc(sizeof(GtkAsyncTask));
    task->fn = fn;
    task->arg = arg;
    g_idle_add(gtkAsyncTaskTrampoline, task);
}

static void runOnGtkThreadAsyncPriority(void (*fn)(void *arg), void *arg, gint priority) {
    GtkAsyncTask *task = malloc(sizeof(GtkAsyncTask));
    task->fn = fn;
    task->arg = arg;
    g_idle_add_full(priority, gtkAsyncTaskTrampoline, task, NULL);
}

typedef struct {
    JavaVM *jvm;
    jobject eventSink;
    jmethodID eventMethod;

    mpv_handle *mpv;
    mpv_render_context *renderCtx;
    atomic_int alive;
    pthread_t eventThread;
    pthread_t resizeThread;

    Window hostXid;
    GtkWidget *window;
    GtkWidget *glArea;
    WebKitWebView *webView;
    atomic_int controlsWebReady;

    pthread_mutex_t controlsMutex;
    char *pendingControlsJson;
} LinuxPlayer;

static LinuxPlayer *gPlayer = NULL;
static pthread_mutex_t gPlayerMutex = PTHREAD_MUTEX_INITIALIZER;

static void callEventSink(LinuxPlayer *player, const char *type, double value) {
    if (!player->eventSink || !player->eventMethod) return;
    JNIEnv *env = NULL;
    int detach = 0;
    jint ret = (*player->jvm)->GetEnv(player->jvm, (void **)&env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        (*player->jvm)->AttachCurrentThread(player->jvm, (void **)&env, NULL);
        detach = 1;
    }
    if (env) {
        jstring jType = (*env)->NewStringUTF(env, type);
        (*env)->CallVoidMethod(env, player->eventSink, player->eventMethod, jType, value);
        (*env)->DeleteLocalRef(env, jType);
    }
    if (detach) (*player->jvm)->DetachCurrentThread(player->jvm);
}

static char *base64Encode(const char *data, size_t len) {
    static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t outLen = 4 * ((len + 2) / 3);
    char *out = malloc(outLen + 1);
    size_t i = 0, j = 0;
    while (i + 2 < len) {
        uint32_t chunk = ((unsigned char)data[i] << 16) | ((unsigned char)data[i + 1] << 8) | (unsigned char)data[i + 2];
        out[j++] = table[(chunk >> 18) & 0x3F];
        out[j++] = table[(chunk >> 12) & 0x3F];
        out[j++] = table[(chunk >> 6) & 0x3F];
        out[j++] = table[chunk & 0x3F];
        i += 3;
    }
    size_t remaining = len - i;
    if (remaining == 1) {
        uint32_t chunk = (unsigned char)data[i] << 16;
        out[j++] = table[(chunk >> 18) & 0x3F];
        out[j++] = table[(chunk >> 12) & 0x3F];
        out[j++] = '=';
        out[j++] = '=';
    } else if (remaining == 2) {
        uint32_t chunk = ((unsigned char)data[i] << 16) | ((unsigned char)data[i + 1] << 8);
        out[j++] = table[(chunk >> 18) & 0x3F];
        out[j++] = table[(chunk >> 12) & 0x3F];
        out[j++] = table[(chunk >> 6) & 0x3F];
        out[j++] = '=';
    }
    out[j] = '\0';
    return out;
}

static void flushPendingControlsJsonTask(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    if (!player->webView || !atomic_load(&player->controlsWebReady)) return;

    pthread_mutex_lock(&player->controlsMutex);
    char *json = player->pendingControlsJson;
    player->pendingControlsJson = NULL;
    pthread_mutex_unlock(&player->controlsMutex);
    if (!json) return;

    char *encoded = base64Encode(json, strlen(json));
    free(json);

    size_t scriptLen = strlen(encoded) + 128;
    char *script = malloc(scriptLen);
    snprintf(script, scriptLen,
        "(function(){if(!window.playerControls)return;window.playerControls(JSON.parse(atob(\"%s\")));})()",
        encoded);
    free(encoded);

    webkit_web_view_evaluate_javascript(player->webView, script, -1, NULL, NULL, NULL, NULL, NULL);
    free(script);
    gtk_widget_queue_draw(GTK_WIDGET(player->webView));
}

static void updateControlsInternal(LinuxPlayer *player, const char *json) {
    pthread_mutex_lock(&player->controlsMutex);
    free(player->pendingControlsJson);
    player->pendingControlsJson = strdup(json);
    pthread_mutex_unlock(&player->controlsMutex);
    runOnGtkThreadAsyncPriority(flushPendingControlsJsonTask, player, G_PRIORITY_HIGH_IDLE);
}

static void selectAudioTrackIdInternal(LinuxPlayer *player, int trackId);
static void selectSubtitleTrackIdInternal(LinuxPlayer *player, int trackId);
static void onControlsWindowDestroyed(GtkWidget *widget, gpointer userData);
static void reattachWindowTask(void *arg);
static void detachWindowTask(void *arg);

static void onScriptMessage(WebKitUserContentManager *manager, WebKitJavascriptResult *jsResult, gpointer userData) {
    (void)manager;
    LinuxPlayer *player = (LinuxPlayer *)userData;
    JSCValue *value = webkit_javascript_result_get_js_value(jsResult);
    if (!value || !jsc_value_is_object(value)) return;

    JSCValue *typeValue = jsc_value_object_get_property(value, "type");
    if (!typeValue || jsc_value_is_undefined(typeValue) || jsc_value_is_null(typeValue)) return;
    char *type = jsc_value_to_string(typeValue);

    JSCValue *rawValue = jsc_value_object_get_property(value, "value");
    int hasValue = rawValue && !jsc_value_is_undefined(rawValue) && !jsc_value_is_null(rawValue);
    double numericValue = hasValue ? jsc_value_to_double(rawValue) : 0.0;

    if (strcmp(type, "controlsReady") == 0) {
        atomic_store(&player->controlsWebReady, 1);
        flushPendingControlsJsonTask(player);
    } else if (strcmp(type, "selectAudioTrack") == 0 && hasValue) {
        selectAudioTrackIdInternal(player, (int)llround(numericValue));
    } else if (strcmp(type, "selectSubtitleTrack") == 0 && hasValue) {
        selectSubtitleTrackIdInternal(player, (int)llround(numericValue));
    } else {
        callEventSink(player, type, numericValue);
    }
    free(type);
}

static gboolean onWebViewMotion(GtkWidget *widget, GdkEventMotion *event, gpointer userData) {
    (void)widget;
    (void)event;
    LinuxPlayer *player = (LinuxPlayer *)userData;
    callEventSink(player, "cursorActivity", 0.0);
    return GDK_EVENT_PROPAGATE;
}

static void *glGetProcAddressWrapper(void *ctx, const char *name) {
    (void)ctx;
    void *addr = (void *)eglGetProcAddress(name);
    if (!addr) addr = (void *)glXGetProcAddressARB((const GLubyte *)name);
    return addr;
}

static void queueGLAreaRenderTask(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    if (player->glArea && GTK_IS_GL_AREA(player->glArea)) {
        gtk_gl_area_queue_render(GTK_GL_AREA(player->glArea));
    }
}

static void mpvRenderUpdateCallback(void *cbCtx) {
    LinuxPlayer *player = (LinuxPlayer *)cbCtx;
    runOnGtkThreadAsync(queueGLAreaRenderTask, player);
}

static void onGLAreaRealize(GtkWidget *widget, gpointer userData) {
    LinuxPlayer *player = (LinuxPlayer *)userData;
    GtkGLArea *area = GTK_GL_AREA(widget);
    gtk_gl_area_make_current(area);
    if (gtk_gl_area_get_error(area) != NULL) {
        return;
    }

    mpv_opengl_init_params glInit = {
        .get_proc_address = glGetProcAddressWrapper,
        .get_proc_address_ctx = NULL,
    };
    int advanced = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInit},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
        {0}
    };
    if (mpv_render_context_create(&player->renderCtx, player->mpv, params) < 0) {
        return;
    }
    mpv_render_context_set_update_callback(player->renderCtx, mpvRenderUpdateCallback, player);
}

static void onGLAreaUnrealize(GtkWidget *widget, gpointer userData) {
    LinuxPlayer *player = (LinuxPlayer *)userData;
    gtk_gl_area_make_current(GTK_GL_AREA(widget));
    if (player->renderCtx) {
        mpv_render_context_set_update_callback(player->renderCtx, NULL, NULL);
        mpv_render_context_free(player->renderCtx);
        player->renderCtx = NULL;
    }
}

static gboolean onGLAreaRender(GtkGLArea *area, GdkGLContext *context, gpointer userData) {
    (void)context;
    LinuxPlayer *player = (LinuxPlayer *)userData;
    if (!player->renderCtx) {
        return TRUE;
    }

    GLint fbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    int scale = gtk_widget_get_scale_factor(GTK_WIDGET(area));
    int width = gtk_widget_get_allocated_width(GTK_WIDGET(area)) * scale;
    int height = gtk_widget_get_allocated_height(GTK_WIDGET(area)) * scale;
    if (width <= 0 || height <= 0) return TRUE;

    mpv_opengl_fbo mpFbo = {.fbo = (int)fbo, .w = width, .h = height, .internal_format = 0};
    int flipY = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &mpFbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {0}
    };
    mpv_render_context_render(player->renderCtx, params);
    return TRUE;
}

typedef struct {
    LinuxPlayer *player;
    char *controlsPageUrl;
} CreateWebViewArgs;

static void createWebViewTask(void *rawArgs) {
    CreateWebViewArgs *args = (CreateWebViewArgs *)rawArgs;
    LinuxPlayer *player = args->player;

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_decorated(GTK_WINDOW(window), FALSE);
    gtk_widget_set_size_request(window, 16, 16);
    g_signal_connect(window, "destroy", G_CALLBACK(onControlsWindowDestroyed), player);

    GdkScreen *screen = gtk_widget_get_screen(window);
    GdkVisual *visual = gdk_screen_get_rgba_visual(screen);
    if (visual) gtk_widget_set_visual(window, visual);
    gtk_widget_set_app_paintable(window, TRUE);

    GtkCssProvider *cssProvider = gtk_css_provider_new();
    gtk_css_provider_load_from_data(
        cssProvider,
        "window, webkitwebview { background-color: transparent; background-image: none; }",
        -1, NULL);
    gtk_style_context_add_provider(
        gtk_widget_get_style_context(window),
        GTK_STYLE_PROVIDER(cssProvider),
        GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_unref(cssProvider);

    GtkWidget *overlay = gtk_overlay_new();
    gtk_container_add(GTK_CONTAINER(window), overlay);

    GtkWidget *glArea = gtk_gl_area_new();
    gtk_gl_area_set_has_alpha(GTK_GL_AREA(glArea), FALSE);
    gtk_gl_area_set_auto_render(GTK_GL_AREA(glArea), FALSE);
    g_signal_connect(glArea, "realize", G_CALLBACK(onGLAreaRealize), player);
    g_signal_connect(glArea, "unrealize", G_CALLBACK(onGLAreaUnrealize), player);
    g_signal_connect(glArea, "render", G_CALLBACK(onGLAreaRender), player);
    gtk_container_add(GTK_CONTAINER(overlay), glArea);

    WebKitUserContentManager *contentManager = webkit_user_content_manager_new();
    webkit_user_content_manager_register_script_message_handler(contentManager, "player");
    g_signal_connect(contentManager, "script-message-received::player", G_CALLBACK(onScriptMessage), player);

    GtkWidget *webView = webkit_web_view_new_with_user_content_manager(contentManager);
    gtk_widget_set_app_paintable(webView, TRUE);
    GdkRGBA transparent = {0, 0, 0, 0};
    webkit_web_view_set_background_color(WEBKIT_WEB_VIEW(webView), &transparent);
    gtk_widget_add_events(webView, GDK_POINTER_MOTION_MASK);
    g_signal_connect(webView, "motion-notify-event", G_CALLBACK(onWebViewMotion), player);
    gtk_overlay_add_overlay(GTK_OVERLAY(overlay), webView);

    gtk_widget_show_all(window);

    gtk_widget_realize(window);
    GdkWindow *gdkWindow = gtk_widget_get_window(window);
    Window ownXid = gdk_x11_window_get_xid(gdkWindow);

    Display *display = gdk_x11_display_get_xdisplay(gdk_display_get_default());
    XReparentWindow(display, ownXid, player->hostXid, 0, 0);

    Window rootReturn;
    int xReturn, yReturn;
    unsigned int widthReturn, heightReturn, borderReturn, depthReturn;
    if (XGetGeometry(display, player->hostXid, &rootReturn, &xReturn, &yReturn,
                      &widthReturn, &heightReturn, &borderReturn, &depthReturn)) {
        gtk_window_resize(GTK_WINDOW(window), (int)widthReturn, (int)heightReturn);
    }

    XMapWindow(display, ownXid);
    XFlush(display);

    player->window = window;
    player->glArea = glArea;
    player->webView = WEBKIT_WEB_VIEW(webView);

    webkit_web_view_load_uri(WEBKIT_WEB_VIEW(webView), args->controlsPageUrl);

    free(args->controlsPageUrl);
    free(args);
}

typedef struct {
    LinuxPlayer *player;
    int width;
    int height;
} TrackControlsGeometryArgs;

static void trackControlsGeometryTask(void *rawArgs) {
    TrackControlsGeometryArgs *args = (TrackControlsGeometryArgs *)rawArgs;
    LinuxPlayer *player = args->player;
    if (player->window && GTK_IS_WINDOW(player->window)) {
        gtk_window_resize(GTK_WINDOW(player->window), args->width, args->height);
    }
    free(args);
}

static void *resizeWatcherThreadFunc(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    Display *display = XOpenDisplay(NULL);
    if (!display) return NULL;

    Window lastHostXid = 0;
    int lastW = -1, lastH = -1;
    while (atomic_load(&player->alive)) {
        if (player->hostXid != lastHostXid) {
            lastHostXid = player->hostXid;
            lastW = -1;
            lastH = -1;
            XSelectInput(display, lastHostXid, StructureNotifyMask);
            XFlush(display);
        }

        while (XPending(display) > 0) {
            XEvent event;
            XNextEvent(display, &event);
            (void)event;
        }

        Window rootReturn;
        int xReturn, yReturn;
        unsigned int widthReturn, heightReturn, borderReturn, depthReturn;
        if (XGetGeometry(display, player->hostXid, &rootReturn, &xReturn, &yReturn,
                          &widthReturn, &heightReturn, &borderReturn, &depthReturn)) {
            if ((int)widthReturn != lastW || (int)heightReturn != lastH) {
                lastW = (int)widthReturn;
                lastH = (int)heightReturn;
                TrackControlsGeometryArgs *args = malloc(sizeof(TrackControlsGeometryArgs));
                args->player = player;
                args->width = (int)widthReturn;
                args->height = (int)heightReturn;
                runOnGtkThreadAsync(trackControlsGeometryTask, args);
            }
        }
        usleep(33000);
    }

    XCloseDisplay(display);
    return NULL;
}

static void *mpvEventThreadFunc(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    while (atomic_load(&player->alive)) {
        mpv_event *event = mpv_wait_event(player->mpv, 0.25);
        if (event->event_id == MPV_EVENT_SHUTDOWN) break;
    }
    return NULL;
}

static char *headerLinesToMpvString(char **headers, int count) {
    if (count <= 0 || !headers) return NULL;
    size_t len = 0;
    for (int i = 0; i < count; i++) {
        if (headers[i]) len += strlen(headers[i]) * 2 + 2;
    }
    if (len == 0) return NULL;
    char *out = malloc(len + 1);
    out[0] = '\0';
    for (int i = 0; i < count; i++) {
        if (!headers[i]) continue;
        if (out[0] != '\0') strcat(out, ",");
        const char *src = headers[i];
        char *dst = out + strlen(out);
        while (*src) {
            if (*src == '\\' || *src == ',') *dst++ = '\\';
            *dst++ = *src++;
        }
        *dst = '\0';
    }
    return out;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void)jvm;
    (void)reserved;
    setlocale(LC_NUMERIC, "C");
    XInitThreads();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env, jobject thiz,
    jlong hostViewPtr,
    jstring sourceUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jstring controlsPageUrl,
    jint decoderPriority, jboolean nvidiaRtxSuperResolutionEnabled,
    jobject eventSink) {
    (void)thiz;
    (void)nvidiaRtxSuperResolutionEnabled;

    ensureGtkThread();

    pthread_mutex_lock(&gPlayerMutex);

    LinuxPlayer *player = gPlayer;
    int isNew = (player == NULL);

    if (isNew) {
        player = calloc(1, sizeof(LinuxPlayer));
        (*env)->GetJavaVM(env, &player->jvm);
        pthread_mutex_init(&player->controlsMutex, NULL);
        atomic_store(&player->alive, 1);
    }

    player->hostXid = (Window)(uintptr_t)hostViewPtr;

    if (player->eventSink) {
        (*env)->DeleteGlobalRef(env, player->eventSink);
        player->eventSink = NULL;
        player->eventMethod = NULL;
    }
    if (eventSink) {
        player->eventSink = (*env)->NewGlobalRef(env, eventSink);
        jclass cls = (*env)->GetObjectClass(env, eventSink);
        player->eventMethod = (*env)->GetMethodID(env, cls, "onPlayerEvent", "(Ljava/lang/String;D)V");
        (*env)->DeleteLocalRef(env, cls);
    }

    if (isNew) {
        setlocale(LC_NUMERIC, "C");
        player->mpv = mpv_create();
        if (!player->mpv) {
            free(player);
            pthread_mutex_unlock(&gPlayerMutex);
            return 0;
        }

        mpv_set_option_string(player->mpv, "config", "no");
        mpv_set_option_string(player->mpv, "osc", "no");
        mpv_set_option_string(player->mpv, "input-default-bindings", "yes");
        mpv_set_option_string(player->mpv, "input-vo-keyboard", "no");
        mpv_set_option_string(player->mpv, "keep-open", "yes");
        mpv_set_option_string(player->mpv, "vo", "libmpv");
        mpv_set_option_string(player->mpv, "hwdec", "auto");
        mpv_set_option_string(player->mpv, "hwdec-codecs", "all");
        mpv_set_option_string(player->mpv, "target-colorspace-hint", "yes");
        mpv_set_option_string(player->mpv, "vd-lavc-threads", "4");
        mpv_set_option_string(player->mpv, "tone-mapping", "auto");
        mpv_set_option_string(player->mpv, "dither-depth", "auto");
        mpv_set_option_string(player->mpv, "deband", "yes");
        mpv_set_option_string(player->mpv, "scale", "spline36");
        mpv_set_option_string(player->mpv, "cscale", "spline36");
        mpv_set_option_string(player->mpv, "demuxer-max-bytes", "512MiB");
        mpv_set_option_string(player->mpv, "demuxer-max-back-bytes", "256MiB");
        mpv_set_option_string(player->mpv, "demuxer-seekable-cache", "yes");
        mpv_set_option_string(player->mpv, "cache-secs", "36000");
        mpv_set_option_string(player->mpv, "hr-seek", "no");
    }

    if (decoderPriority == 0) {
        mpv_set_option_string(player->mpv, "vd-lavc-software-fallback", "no");
        mpv_set_option_string(player->mpv, "hwdec", "auto");
    } else if (decoderPriority == 2) {
        mpv_set_option_string(player->mpv, "hwdec", "no");
        mpv_set_option_string(player->mpv, "vd-lavc-software-fallback", "yes");
    } else {
        mpv_set_option_string(player->mpv, "hwdec", "auto");
        mpv_set_option_string(player->mpv, "vd-lavc-software-fallback", "yes");
    }

    jsize headerCount = headerLines ? (*env)->GetArrayLength(env, headerLines) : 0;
    if (headerCount > 0) {
        char **headers = calloc(headerCount, sizeof(char *));
        for (jsize i = 0; i < headerCount; i++) {
            jstring s = (jstring)(*env)->GetObjectArrayElement(env, headerLines, i);
            const char *h = (*env)->GetStringUTFChars(env, s, NULL);
            if (h) headers[i] = strdup(h);
            (*env)->ReleaseStringUTFChars(env, s, h);
            (*env)->DeleteLocalRef(env, s);
        }
        char *headerString = headerLinesToMpvString(headers, headerCount);
        mpv_set_option_string(player->mpv, "http-header-fields", headerString ? headerString : "");
        free(headerString);
        for (jsize i = 0; i < headerCount; i++) free(headers[i]);
        free(headers);
    } else {
        mpv_set_option_string(player->mpv, "http-header-fields", "");
    }

    if (isNew) {
        if (mpv_initialize(player->mpv) < 0) {
            mpv_terminate_destroy(player->mpv);
            free(player);
            pthread_mutex_unlock(&gPlayerMutex);
            return 0;
        }

        const char *controlsUrlChars = (*env)->GetStringUTFChars(env, controlsPageUrl, NULL);
        CreateWebViewArgs *webViewArgs = malloc(sizeof(CreateWebViewArgs));
        webViewArgs->player = player;
        webViewArgs->controlsPageUrl = strdup(controlsUrlChars);
        (*env)->ReleaseStringUTFChars(env, controlsPageUrl, controlsUrlChars);
        runOnGtkThreadSync(createWebViewTask, webViewArgs);

        pthread_create(&player->resizeThread, NULL, resizeWatcherThreadFunc, player);
        pthread_create(&player->eventThread, NULL, mpvEventThreadFunc, player);

        gPlayer = player;
    } else {
        runOnGtkThreadSync(reattachWindowTask, player);
        if (player->renderCtx) {
            mpv_render_context_set_update_callback(player->renderCtx, mpvRenderUpdateCallback, player);
        }
    }

    const char *src = sourceUrl ? (*env)->GetStringUTFChars(env, sourceUrl, NULL) : NULL;
    if (src) {
        if (initialPositionMs > 0) {
            char startOpt[80];
            snprintf(startOpt, sizeof(startOpt), "start=%.3f", (double)initialPositionMs / 1000.0);
            const char *cmd[] = {"loadfile", src, "replace", "-1", startOpt, NULL};
            mpv_command_async(player->mpv, 0, cmd);
        } else {
            const char *cmd[] = {"loadfile", src, "replace", NULL};
            mpv_command_async(player->mpv, 0, cmd);
        }
        (*env)->ReleaseStringUTFChars(env, sourceUrl, src);
    }

    int muteFlag = 0;
    mpv_set_property(player->mpv, "mute", MPV_FORMAT_FLAG, &muteFlag);
    int pauseFlag = playWhenReady ? 0 : 1;
    mpv_set_property(player->mpv, "pause", MPV_FORMAT_FLAG, &pauseFlag);

    pthread_mutex_unlock(&gPlayerMutex);
    return (jlong)(intptr_t)player;
}

static void onControlsWindowDestroyed(GtkWidget *widget, gpointer userData) {
    (void)widget;
    LinuxPlayer *player = (LinuxPlayer *)userData;
    player->window = NULL;
    player->glArea = NULL;
    player->webView = NULL;
}

static void reattachWindowTask(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    if (!player->window || !GTK_IS_WIDGET(player->window)) return;
    GdkWindow *gdkWindow = gtk_widget_get_window(player->window);
    if (!gdkWindow) return;

    Window ownXid = gdk_x11_window_get_xid(gdkWindow);
    Display *display = gdk_x11_display_get_xdisplay(gdk_display_get_default());
    XReparentWindow(display, ownXid, player->hostXid, 0, 0);

    Window rootReturn;
    int xReturn, yReturn;
    unsigned int widthReturn, heightReturn, borderReturn, depthReturn;
    if (XGetGeometry(display, player->hostXid, &rootReturn, &xReturn, &yReturn,
                      &widthReturn, &heightReturn, &borderReturn, &depthReturn)) {
        gtk_window_resize(GTK_WINDOW(player->window), (int)widthReturn, (int)heightReturn);
    }

    XMapWindow(display, ownXid);
    gtk_widget_show(player->window);
    if (player->glArea && GTK_IS_GL_AREA(player->glArea)) {
        gtk_gl_area_set_auto_render(GTK_GL_AREA(player->glArea), TRUE);
        gtk_gl_area_queue_render(GTK_GL_AREA(player->glArea));
    }
    XFlush(display);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    if (!handle) return;
    LinuxPlayer *player = (LinuxPlayer *)(intptr_t)handle;

    if (player->renderCtx) {
        mpv_render_context_set_update_callback(player->renderCtx, NULL, NULL);
    }

    if (player->mpv) {
        int muteFlag = 1;
        mpv_set_property(player->mpv, "mute", MPV_FORMAT_FLAG, &muteFlag);
        int pauseFlag = 1;
        mpv_set_property(player->mpv, "pause", MPV_FORMAT_FLAG, &pauseFlag);
        const char *cmd[] = {"stop", NULL};
        mpv_command_async(player->mpv, 0, cmd);
    }

    runOnGtkThreadSync(detachWindowTask, player);
}

static void detachWindowTask(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    if (!player->window || !GTK_IS_WIDGET(player->window)) return;
    GdkWindow *gdkWindow = gtk_widget_get_window(player->window);
    if (!gdkWindow) return;

    Display *display = gdk_x11_display_get_xdisplay(gdk_display_get_default());
    Window ownXid = gdk_x11_window_get_xid(gdkWindow);
    Window root = DefaultRootWindow(display);

    XUnmapWindow(display, ownXid);
    XReparentWindow(display, ownXid, root, 0, 0);
    XFlush(display);
}

static LinuxPlayer *getPlayer(jlong handle) {
    return handle ? (LinuxPlayer *)(intptr_t)handle : NULL;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(
    JNIEnv *env, jobject thiz, jlong handle, jstring json) {
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return;
    const char *chars = (*env)->GetStringUTFChars(env, json, NULL);
    if (chars) updateControlsInternal(player, chars);
    (*env)->ReleaseStringUTFChars(env, json, chars);
}

static void grabFocusTask(void *arg) {
    LinuxPlayer *player = (LinuxPlayer *)arg;
    if (player->webView) gtk_widget_grab_focus(GTK_WIDGET(player->webView));
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_requestFocus(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return;
    runOnGtkThreadAsync(grabFocusTask, player);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(
    JNIEnv *env, jobject thiz, jlong handle, jboolean paused) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    int flag = paused ? 1 : 0;
    mpv_set_property(player->mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return JNI_TRUE;
    int flag = 1;
    mpv_get_property(player->mpv, "pause", MPV_FORMAT_FLAG, &flag);
    return flag ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(
    JNIEnv *env, jobject thiz, jlong handle, jlong posMs) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    char buf[64];
    snprintf(buf, sizeof(buf), "%f", (double)posMs / 1000.0);
    const char *cmd[] = {"seek", buf, "absolute+keyframes", NULL};
    mpv_command_async(player->mpv, 0, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(
    JNIEnv *env, jobject thiz, jlong handle, jlong offMs) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    char buf[64];
    snprintf(buf, sizeof(buf), "%f", (double)offMs / 1000.0);
    const char *cmd[] = {"seek", buf, "relative+keyframes", NULL};
    mpv_command_async(player->mpv, 0, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(
    JNIEnv *env, jobject thiz, jlong handle, jfloat speed) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    double clamped = speed < 0.25 ? 0.25 : (speed > 4.0 ? 4.0 : speed);
    mpv_set_property(player->mpv, "speed", MPV_FORMAT_DOUBLE, &clamped);
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return 1.0f;
    double v = 1.0;
    mpv_get_property(player->mpv, "speed", MPV_FORMAT_DOUBLE, &v);
    return (jfloat)v;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_adjustVolume(
    JNIEnv *env, jobject thiz, jlong handle, jfloat delta) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    double current = 100.0;
    mpv_get_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &current);
    double next = current + delta;
    if (next < 0.0) next = 0.0;
    if (next > 100.0) next = 100.0;
    mpv_set_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &next);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(
    JNIEnv *env, jobject thiz, jlong handle, jfloat level) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    double next = (double)level * 100.0;
    if (next < 0.0) next = 0.0;
    if (next > 100.0) next = 100.0;
    mpv_set_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &next);
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return 1.0f;
    double v = 100.0;
    mpv_get_property(player->mpv, "volume", MPV_FORMAT_DOUBLE, &v);
    if (v < 0.0) v = 0.0;
    if (v > 100.0) v = 100.0;
    return (jfloat)(v / 100.0);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(
    JNIEnv *env, jobject thiz, jlong handle, jint mode) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    switch (mode) {
    case 1:
    case 2:
        mpv_set_option_string(player->mpv, "keepaspect", "yes");
        mpv_set_option_string(player->mpv, "panscan", "1.0");
        mpv_set_option_string(player->mpv, "video-unscaled", "no");
        break;
    case 3:
        mpv_set_option_string(player->mpv, "keepaspect", "no");
        mpv_set_option_string(player->mpv, "panscan", "0.0");
        mpv_set_option_string(player->mpv, "video-unscaled", "no");
        break;
    default:
        mpv_set_option_string(player->mpv, "keepaspect", "yes");
        mpv_set_option_string(player->mpv, "panscan", "0.0");
        mpv_set_option_string(player->mpv, "video-unscaled", "no");
        break;
    }
}

static double doubleProperty(LinuxPlayer *player, const char *name, double fallback) {
    double v = fallback;
    if (player->mpv) mpv_get_property(player->mpv, name, MPV_FORMAT_DOUBLE, &v);
    return v;
}

static int64_t int64Property(LinuxPlayer *player, const char *name, int64_t fallback) {
    int64_t v = fallback;
    if (player->mpv) mpv_get_property(player->mpv, name, MPV_FORMAT_INT64, &v);
    return v;
}

static int flagProperty(LinuxPlayer *player, const char *name, int fallback) {
    int v = fallback;
    if (player->mpv) mpv_get_property(player->mpv, name, MPV_FORMAT_FLAG, &v);
    return v;
}

static char *stringProperty(LinuxPlayer *player, const char *name) {
    char *v = NULL;
    if (player->mpv) mpv_get_property(player->mpv, name, MPV_FORMAT_STRING, &v);
    return v;
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return 0;
    return (jlong)llround(doubleProperty(player, "duration", 0.0) * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return 0;
    return (jlong)llround(doubleProperty(player, "time-pos", 0.0) * 1000.0);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return 0;
    double pos = doubleProperty(player, "time-pos", 0.0);
    double cache = doubleProperty(player, "demuxer-cache-duration", 0.0);
    double buffered = pos + cache;
    if (buffered < 0.0) buffered = 0.0;
    return (jlong)llround(buffered * 1000.0);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return JNI_TRUE;
    int paused = flagProperty(player, "pause", 1);
    int eof = flagProperty(player, "eof-reached", 0);
    int idle = flagProperty(player, "core-idle", 1);
    int bufferingCache = flagProperty(player, "paused-for-cache", 0);
    double duration = doubleProperty(player, "duration", 0.0);
    int64_t trackCount = int64Property(player, "track-list/count", 0);
    int fileReady = duration > 0.0 || trackCount > 0;
    int notReady = idle && !eof && (!paused || !fileReady);
    return (notReady || bufferingCache) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return JNI_FALSE;
    return flagProperty(player, "eof-reached", 0) ? JNI_TRUE : JNI_FALSE;
}

static void appendJsonEscaped(char **buf, size_t *len, size_t *cap, const char *str) {
    if (!str) return;
    for (const unsigned char *p = (const unsigned char *)str; *p; p++) {
        const char *rep = NULL;
        char single[8];
        switch (*p) {
        case '\\': rep = "\\\\"; break;
        case '"': rep = "\\\""; break;
        case '\n': rep = "\\n"; break;
        case '\r': rep = "\\r"; break;
        case '\t': rep = "\\t"; break;
        default:
            if (*p < 0x20) {
                snprintf(single, sizeof(single), "\\u%04x", *p);
                rep = single;
            }
        }
        if (rep) {
            size_t repLen = strlen(rep);
            if (*len + repLen + 1 > *cap) {
                *cap = (*len + repLen + 1) * 2;
                *buf = realloc(*buf, *cap);
            }
            memcpy(*buf + *len, rep, repLen);
            *len += repLen;
            (*buf)[*len] = '\0';
        } else {
            if (*len + 2 > *cap) {
                *cap *= 2;
                *buf = realloc(*buf, *cap);
            }
            (*buf)[(*len)++] = (char)*p;
            (*buf)[*len] = '\0';
        }
    }
}

static char *tracksJsonForType(LinuxPlayer *player, const char *wantedType) {
    size_t cap = 256;
    size_t len = 0;
    char *out = malloc(cap);
    out[0] = '\0';
#define APPEND(s) do { \
        size_t sLen = strlen(s); \
        if (len + sLen + 1 > cap) { cap = (len + sLen + 1) * 2; out = realloc(out, cap); } \
        memcpy(out + len, s, sLen); len += sLen; out[len] = '\0'; \
    } while (0)

    APPEND("[");
    int64_t count = int64Property(player, "track-list/count", 0);
    int logicalIndex = 0;
    int first = 1;
    for (int64_t i = 0; i < count; i++) {
        char prop[96];
        snprintf(prop, sizeof(prop), "track-list/%lld/type", (long long)i);
        char *type = stringProperty(player, prop);
        if (!type || strcmp(type, wantedType) != 0) {
            mpv_free(type);
            continue;
        }
        mpv_free(type);

        snprintf(prop, sizeof(prop), "track-list/%lld/id", (long long)i);
        int64_t trackId = int64Property(player, prop, logicalIndex + 1);
        snprintf(prop, sizeof(prop), "track-list/%lld/title", (long long)i);
        char *title = stringProperty(player, prop);
        snprintf(prop, sizeof(prop), "track-list/%lld/lang", (long long)i);
        char *lang = stringProperty(player, prop);
        snprintf(prop, sizeof(prop), "track-list/%lld/selected", (long long)i);
        int selected = flagProperty(player, prop, 0);
        snprintf(prop, sizeof(prop), "track-list/%lld/forced", (long long)i);
        int forced = flagProperty(player, prop, 0);

        char idStr[32];
        snprintf(idStr, sizeof(idStr), "%lld", (long long)trackId);
        char fallbackLabel[32];
        snprintf(fallbackLabel, sizeof(fallbackLabel), "%s %d", strcmp(wantedType, "sub") == 0 ? "Subtitle" : "Track", logicalIndex + 1);
        const char *label = (title && title[0]) ? title : ((lang && lang[0]) ? lang : fallbackLabel);

        if (!first) APPEND(",");
        first = 0;
        APPEND("{\"index\":");
        char numBuf[32];
        snprintf(numBuf, sizeof(numBuf), "%d", logicalIndex);
        APPEND(numBuf);
        APPEND(",\"id\":\"");
        appendJsonEscaped(&out, &len, &cap, idStr);
        APPEND("\",\"label\":\"");
        appendJsonEscaped(&out, &len, &cap, label);
        APPEND("\",\"language\":\"");
        appendJsonEscaped(&out, &len, &cap, lang ? lang : "");
        APPEND("\",\"selected\":");
        APPEND(selected ? "true" : "false");
        APPEND(",\"forced\":");
        APPEND(forced ? "true" : "false");
        APPEND("}");

        mpv_free(title);
        mpv_free(lang);
        logicalIndex++;
    }
    APPEND("]");
#undef APPEND
    return out;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return (*env)->NewStringUTF(env, "[]");
    char *json = tracksJsonForType(player, "audio");
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return (*env)->NewStringUTF(env, "[]");
    char *json = tracksJsonForType(player, "sub");
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

static void selectAudioTrackIdInternal(LinuxPlayer *player, int trackId) {
    if (!player->mpv) return;
    int64_t id = trackId;
    mpv_set_property(player->mpv, "aid", MPV_FORMAT_INT64, &id);
}

static void selectSubtitleTrackIdInternal(LinuxPlayer *player, int trackId) {
    if (!player->mpv) return;
    if (trackId < 0) {
        mpv_set_option_string(player->mpv, "sid", "no");
        return;
    }
    int64_t id = trackId;
    mpv_set_property(player->mpv, "sid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(
    JNIEnv *env, jobject thiz, jlong handle, jint trackId) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (player) selectAudioTrackIdInternal(player, trackId);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(
    JNIEnv *env, jobject thiz, jlong handle, jint trackId) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (player) selectSubtitleTrackIdInternal(player, trackId);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(
    JNIEnv *env, jobject thiz, jlong handle, jstring url) {
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    const char *chars = (*env)->GetStringUTFChars(env, url, NULL);
    if (chars && chars[0]) {
        const char *cmd[] = {"sub-add", chars, "select", NULL};
        mpv_command_async(player->mpv, 0, cmd);
    }
    (*env)->ReleaseStringUTFChars(env, url, chars);
}

static void removeExternalSubtitleTracks(LinuxPlayer *player) {
    if (!player->mpv) return;
    int64_t count = int64Property(player, "track-list/count", 0);
    for (int64_t i = count - 1; i >= 0; i--) {
        char prop[96];
        snprintf(prop, sizeof(prop), "track-list/%lld/type", (long long)i);
        char *type = stringProperty(player, prop);
        snprintf(prop, sizeof(prop), "track-list/%lld/external", (long long)i);
        int external = flagProperty(player, prop, 0);
        if (type && strcmp(type, "sub") == 0 && external) {
            snprintf(prop, sizeof(prop), "track-list/%lld/id", (long long)i);
            int64_t trackId = int64Property(player, prop, -1);
            if (trackId >= 0) {
                char idStr[32];
                snprintf(idStr, sizeof(idStr), "%lld", (long long)trackId);
                const char *cmd[] = {"sub-remove", idStr, NULL};
                mpv_command(player->mpv, cmd);
            }
        }
        mpv_free(type);
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return;
    removeExternalSubtitleTracks(player);
    if (player->mpv) mpv_set_option_string(player->mpv, "sid", "no");
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(
    JNIEnv *env, jobject thiz, jlong handle, jint trackId) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player) return;
    removeExternalSubtitleTracks(player);
    if (trackId >= 0) {
        selectSubtitleTrackIdInternal(player, trackId);
    } else if (player->mpv) {
        mpv_set_option_string(player->mpv, "sid", "no");
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(
    JNIEnv *env, jobject thiz, jlong handle, jint delayMs) {
    (void)env;
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;
    int clamped = delayMs < -60000 ? -60000 : (delayMs > 60000 ? 60000 : delayMs);
    double seconds = (double)clamped / 1000.0;
    mpv_set_property(player->mpv, "sub-delay", MPV_FORMAT_DOUBLE, &seconds);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env, jobject thiz, jlong handle,
    jstring textColor, jstring backgroundColor, jstring outlineColor,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos) {
    (void)thiz;
    LinuxPlayer *player = getPlayer(handle);
    if (!player || !player->mpv) return;

    const char *text = (*env)->GetStringUTFChars(env, textColor, NULL);
    const char *background = (*env)->GetStringUTFChars(env, backgroundColor, NULL);
    const char *outline = (*env)->GetStringUTFChars(env, outlineColor, NULL);

    mpv_set_option_string(player->mpv, "sub-ass-override", "force");
    mpv_set_option_string(player->mpv, "sub-color", (text && text[0]) ? text : "#FFFFFFFF");
    mpv_set_option_string(player->mpv, "sub-back-color", (background && background[0]) ? background : "#00000000");
    mpv_set_option_string(player->mpv, "sub-outline-color", (outline && outline[0]) ? outline : "#FF000000");
    mpv_set_option_string(player->mpv, "sub-border-style",
        (background && strncmp(background, "#00", 3) == 0) ? "outline-and-shadow" : "opaque-box");
    mpv_set_option_string(player->mpv, "sub-bold", bold ? "yes" : "no");

    double outlineD = outlineSize < 0.0f ? 0.0 : (outlineSize > 8.0f ? 8.0 : outlineSize);
    double sizeD = fontSize < 18.0f ? 18.0 : (fontSize > 96.0f ? 96.0 : fontSize);
    int64_t posD = subPos < 0 ? 0 : (subPos > 150 ? 150 : subPos);
    mpv_set_property(player->mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outlineD);
    mpv_set_property(player->mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &sizeD);
    mpv_set_property(player->mpv, "sub-pos", MPV_FORMAT_INT64, &posD);

    (*env)->ReleaseStringUTFChars(env, textColor, text);
    (*env)->ReleaseStringUTFChars(env, backgroundColor, background);
    (*env)->ReleaseStringUTFChars(env, outlineColor, outline);
}
