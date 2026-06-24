#define _GNU_SOURCE
#include <jni.h>
#include <mpv/client.h>
#include <mpv/render.h>
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
/*  Wayland detection                                                  */
/* ------------------------------------------------------------------ */
static int detect_wayland(void) {
    const char *session_type = getenv("XDG_SESSION_TYPE");
    if (session_type && strstr(session_type, "wayland")) {
        return 1;
    }
    const char *display = getenv("WAYLAND_DISPLAY");
    if (display && display[0] != '\0') {
        return 1;
    }
    return 0;
}

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
    int gpuMode; /* 0 = SW rendering, 1 = vo=gpu-next with X11 wid, 2 = Wayland SW (optimized) */

    /* only used in SW mode */
    pthread_t renderThread;
    pthread_mutex_t frameMutex;
    int frameW;
    int frameH;
    int frameStride;
    char *frameData;
    volatile int frameReady;

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

static void renderFrameToBuffer(CreateTask *task) {
    if (!task->renderCtx) return;

    int flags = mpv_render_context_update(task->renderCtx);
    if (!(flags & MPV_RENDER_UPDATE_FRAME)) return;

    int64_t w = 0, h = 0;
    mpv_get_property(task->mpv, "dwidth", MPV_FORMAT_INT64, &w);
    mpv_get_property(task->mpv, "dheight", MPV_FORMAT_INT64, &h);
    if (w <= 0 || h <= 0) return;

    pthread_mutex_lock(&task->frameMutex);

    int stride = (int)(w * 4);
    if (w != task->frameW || h != task->frameH) {
        char *newBuf = realloc(task->frameData, stride * (int)h);
        if (!newBuf) { pthread_mutex_unlock(&task->frameMutex); return; }
        task->frameData = newBuf;
        task->frameW = (int)w;
        task->frameH = (int)h;
        task->frameStride = stride;
    }

    int render_w = (int)w;
    int render_h = (int)h;
    int render_stride = stride;
    void *render_ptr = task->frameData;

    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_SW_SIZE, &(int[2]){render_w, render_h}},
        {MPV_RENDER_PARAM_SW_FORMAT, (char *)"rgb0"},
        {MPV_RENDER_PARAM_SW_STRIDE, &render_stride},
        {MPV_RENDER_PARAM_SW_POINTER, render_ptr},
        {0}
    };
    if (mpv_render_context_render(task->renderCtx, params) < 0) {
        DBG("renderFrameToBuffer: mpv_render_context_render failed\n");
    }

    task->frameReady = 1;
    pthread_mutex_unlock(&task->frameMutex);
}

static void mpvWakeupCallback(void *data) {
    (void)data;
}

static void *renderThreadFunc(void *data) {
    CreateTask *task = (CreateTask *)data;

    while (task->alive) {
        if (task->mpv) {
            while (1) {
                mpv_event *event = mpv_wait_event(task->mpv, 0.016);
                if (event->event_id == MPV_EVENT_NONE) break;
                switch (event->event_id) {
                    case MPV_EVENT_START_FILE:
                        DBG("[mpv] start-file\n");
                        break;
                    case MPV_EVENT_END_FILE:
                        DBG("[mpv] end-file (reason=%d)\n",
                            ((struct mpv_event_end_file*)event->data)->reason);
                        break;
                    case MPV_EVENT_FILE_LOADED:
                        DBG("[mpv] file-loaded\n");
                        break;
                    case MPV_EVENT_PLAYBACK_RESTART:
                        DBG("[mpv] playback-restart\n");
                        break;
                    case MPV_EVENT_VIDEO_RECONFIG:
                        DBG("[mpv] video-reconfig\n");
                        break;
                    case MPV_EVENT_AUDIO_RECONFIG:
                        DBG("[mpv] audio-reconfig\n");
                        break;
                    default:
                        break;
                }
            }
        }

        if (!task->alive) break;
        renderFrameToBuffer(task);
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

static void jb_node(JsonBuf *b, const mpv_node *node) {
    switch (node->format) {
        case MPV_FORMAT_STRING:
            jb_escape(b, node->u.string);
            return;
        case MPV_FORMAT_INT64:
            jb_append_f(b, "%" PRId64, node->u.int64);
            return;
        case MPV_FORMAT_DOUBLE:
            if (isnan(node->u.double_) || isinf(node->u.double_))
                jb_append(b, "0");
            else
                jb_append_f(b, "%g", node->u.double_);
            return;
        case MPV_FORMAT_FLAG:
            jb_append(b, node->u.flag ? "true" : "false");
            return;
        case MPV_FORMAT_NODE_MAP:
            if (!node->u.list) { jb_append(b, "{}"); return; }
            jb_append_c(b, '{');
            for (int i = 0; i < node->u.list->num; i++) {
                if (i > 0) jb_append_c(b, ',');
                jb_escape(b, node->u.list->keys[i]);
                jb_append_c(b, ':');
                jb_node(b, &node->u.list->values[i]);
            }
            jb_append_c(b, '}');
            return;
        case MPV_FORMAT_NODE_ARRAY:
            if (!node->u.list) { jb_append(b, "[]"); return; }
            jb_append_c(b, '[');
            for (int i = 0; i < node->u.list->num; i++) {
                if (i > 0) jb_append_c(b, ',');
                jb_node(b, &node->u.list->values[i]);
            }
            jb_append_c(b, ']');
            return;
        default:
            jb_append(b, "null");
    }
}

static char *tracks_to_json(mpv_handle *mpv) {
    mpv_node tracks;
    if (mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &tracks) < 0)
        return strdup("[]");
    JsonBuf b;
    jb_init(&b);
    jb_node(&b, &tracks);
    mpv_free_node_contents(&tracks);
    return b.s ? b.s : strdup("[]");
}

static char *tracks_json_for_type(mpv_handle *mpv, const char *type) {
    mpv_node tracks;
    if (mpv_get_property(mpv, "track-list", MPV_FORMAT_NODE, &tracks) < 0)
        return strdup("[]");

    JsonBuf b;
    jb_init(&b);
    jb_append_c(&b, '[');

    if (tracks.format == MPV_FORMAT_NODE_ARRAY && tracks.u.list) {
        int first = 1;
        for (int i = 0; i < tracks.u.list->num; i++) {
            mpv_node *node = &tracks.u.list->values[i];
            if (node->format != MPV_FORMAT_NODE_MAP || !node->u.list) continue;

            const char *node_type = NULL;
            int track_id = 0;
            int selected = 0;
            const char *lang = NULL;
            const char *label = NULL;

            for (int j = 0; j < node->u.list->num; j++) {
                const char *key = node->u.list->keys[j];
                mpv_node *val = &node->u.list->values[j];
                if (strcmp(key, "type") == 0 && val->format == MPV_FORMAT_STRING)
                    node_type = val->u.string;
                else if (strcmp(key, "id") == 0 && val->format == MPV_FORMAT_INT64)
                    track_id = (int)val->u.int64;
                else if (strcmp(key, "selected") == 0 && val->format == MPV_FORMAT_STRING)
                    selected = (strcmp(val->u.string, "yes") == 0) ? 1 : 0;
                else if (strcmp(key, "lang") == 0 && val->format == MPV_FORMAT_STRING)
                    lang = val->u.string;
                else if (strcmp(key, "title") == 0 && val->format == MPV_FORMAT_STRING)
                    label = val->u.string;
                else if (strcmp(key, "decoder-desc") == 0 && val->format == MPV_FORMAT_STRING)
                    { /* available but prefer lang/title for label */ }
            }

            if (!node_type || strcmp(node_type, type) != 0) continue;

            if (!first) jb_append_c(&b, ',');
            first = 0;

            jb_append_c(&b, '{');
            jb_append_f(&b, "\"id\":\"%d\"", track_id);
            jb_append_f(&b, ",\"index\":%d", track_id - 1);
            jb_append_c(&b, ','); jb_escape(&b, "label"); jb_append_c(&b, ':');
            jb_escape(&b, label ? label : (lang ? lang : "Track"));
            jb_append_c(&b, ','); jb_escape(&b, "language"); jb_append_c(&b, ':');
            jb_escape(&b, lang ? lang : "");
            jb_append_c(&b, ','); jb_escape(&b, "selected"); jb_append_c(&b, ':');
            jb_append(&b, selected ? "true" : "false");
            jb_append_c(&b, '}');
        }
    }

    jb_append_c(&b, ']');
    mpv_free_node_contents(&tracks);
    return b.s ? b.s : strdup("[]");
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
    (void)decoderPriority; (void)nvidiaRtxSuperResolutionEnabled;

    int isGpuMode = (hostViewPtr != 0);

    DBG("create() called (%s mode)\n", isGpuMode ? "GPU (gpu-next + wid)" : "SW (libmpv)");

    CreateTask *task = calloc(1, sizeof(CreateTask));
    if (!task) { DBG("create: calloc failed\n"); return 0; }

    (*env)->GetJavaVM(env, &task->jvm);
    task->alive = 1;
    task->gpuMode = isGpuMode;

    if (!isGpuMode) {
        pthread_mutex_init(&task->frameMutex, NULL);
    }

    if (eventSink) {
        task->eventSink = (*env)->NewGlobalRef(env, eventSink);
        jclass cls = (*env)->GetObjectClass(env, eventSink);
        task->eventMethod = (*env)->GetMethodID(env, cls,
            "onPlayerEvent", "(Ljava/lang/String;D)V");
        (*env)->DeleteLocalRef(env, cls);
    }

    {
        const char *src = sourceUrl
            ? (*env)->GetStringUTFChars(env, sourceUrl, NULL) : NULL;
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
        if (task->eventSink && task->jvm) (*env)->DeleteGlobalRef(env, task->eventSink);
        if (!isGpuMode) pthread_mutex_destroy(&task->frameMutex);
        free(task);
        return 0;
    }

    /* -- GPU mode: render via X11 window -- */
    if (isGpuMode) {
        mpv_set_option_string(task->mpv, "vo", "gpu-next");
        mpv_set_option_string(task->mpv, "gpu-api", "opengl");
        mpv_set_option_string(task->mpv, "wid", "");
        int64_t wid = (int64_t)hostViewPtr;
        mpv_set_option(task->mpv, "wid", MPV_FORMAT_INT64, &wid);
    } else {
        /* -- SW mode: render via libmpv into CPU buffers -- */
        mpv_set_option_string(task->mpv, "vo", "libmpv");
        if (detect_wayland()) {
            DBG("create: Wayland detected, using optimized SW rendering\n");
            task->gpuMode = 2;
            mpv_set_option_string(task->mpv, "gpu-context", "wayland");
            mpv_set_option_string(task->mpv, "gpu-api", "opengl");
        }
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

    mpv_set_option_string(task->mpv, "vd-lavc-dr", "no");
    mpv_set_option_string(task->mpv, "vd-lavc-fast", "yes");
    mpv_set_option_string(task->mpv, "video-latency-hacks", "yes");
    mpv_set_option_string(task->mpv, "video-reversal-buffer", "no");
    mpv_set_option_string(task->mpv, "keep-open", "no");
    mpv_set_option_string(task->mpv, "vd-queue-enable", "no");

    mpv_set_option_string(task->mpv, "icc-profile-auto", "yes");
    mpv_set_option_string(task->mpv, "target-prim", "auto");
    mpv_set_option_string(task->mpv, "target-trc", "auto");
    mpv_set_option_string(task->mpv, "tone-mapping", "bt.2390");
    mpv_set_option_string(task->mpv, "deband", "yes");
    mpv_set_option_string(task->mpv, "dither-depth", "auto");
    mpv_set_option_string(task->mpv, "scale", "spline36");
    mpv_set_option_string(task->mpv, "cscale", "spline36");
    mpv_set_option_string(task->mpv, "video-output-levels", "auto");
    mpv_set_option_string(task->mpv, "gamma-factor", "1.0");

    if (task->nheaders > 0 && task->headers) {
        size_t len = 0;
        for (int i = 0; i < task->nheaders; i++)
            if (task->headers[i]) len += strlen(task->headers[i]) + 1;
        if (len > 0) {
            char *hdr = malloc(len + 1);
            hdr[0] = '\0';
            for (int i = 0; i < task->nheaders; i++) {
                if (i > 0 && task->headers[i]) strcat(hdr, "\n");
                if (task->headers[i]) strcat(hdr, task->headers[i]);
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
        if (task->eventSink && task->jvm) (*env)->DeleteGlobalRef(env, task->eventSink);
        if (!isGpuMode) pthread_mutex_destroy(&task->frameMutex);
        free(task);
        return 0;
    }

    /* -- GPU mode: rendering handled by mpv directly -- */
    if (isGpuMode) {
        DBG("create: GPU mode active, mpv renders directly to X11 window 0x%lx\n", (unsigned long)hostViewPtr);
    } else {
        /* -- SW mode: create render context + thread -- */
        mpv_set_property_string(task->mpv, "vo", "libmpv");

        int advanced = 1;
        mpv_render_param render_params[] = {
            {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_SW},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
            {0}
        };
        if (mpv_render_context_create(&task->renderCtx, task->mpv, render_params) < 0) {
            DBG("create: mpv_render_context_create failed\n");
            mpv_terminate_destroy(task->mpv);
            callEventSink(env, task->jvm, task->eventSink, task->eventMethod, "error", 7.0);
            free(task->sourceUrl);
            if (task->headers) { for (int i = 0; i < task->nheaders; i++) free(task->headers[i]); free(task->headers); }
            if (task->eventSink && task->jvm) (*env)->DeleteGlobalRef(env, task->eventSink);
            pthread_mutex_destroy(&task->frameMutex);
            free(task);
            return 0;
        }

        mpv_render_context_set_update_callback(task->renderCtx, mpvWakeupCallback, task);

        DBG("create: SW mode active, starting render thread\n");
    }

    DBG("create: mpv initialized, loading source...\n");

    if (task->sourceUrl) {
        const char *cmd[] = {"loadfile", task->sourceUrl, NULL};
        mpv_command_async(task->mpv, 0, cmd);
        DBG("create: loadfile sent (async)\n");
    }

    if (!playWhenReady) {
        mpv_set_property_string(task->mpv, "pause", "yes");
    }
    if (initialPositionMs > 0) {
        double pos = (double)initialPositionMs / 1000.0;
        char posStr[64];
        snprintf(posStr, sizeof(posStr), "%f", pos);
        mpv_set_property_string(task->mpv, "time-pos", posStr);
    }

    if (!isGpuMode) {
        pthread_create(&task->renderThread, NULL, renderThreadFunc, task);
    }

    DBG("create: returning handle\n");
    return (jlong)(intptr_t)task;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (!handle) return;
    CreateTask *task = (CreateTask *)(intptr_t)handle;

    task->alive = 0;

    if (task->gpuMode == 1) {
        if (task->mpv) mpv_wakeup(task->mpv);
    } else {
        if (task->mpv) mpv_wakeup(task->mpv);
        if (task->renderThread) {
            pthread_join(task->renderThread, NULL);
        }
        if (task->renderCtx) {
            mpv_render_context_free(task->renderCtx);
            task->renderCtx = NULL;
        }
    }

    if (task->mpv) {
        mpv_terminate_destroy(task->mpv);
        task->mpv = NULL;
    }

    if (task->eventSink && task->jvm) {
        JavaVM *jvm = task->jvm;
        JNIEnv *e = NULL; int detach = 0;
        jint ret = (*jvm)->GetEnv(jvm, (void**)&e, JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) { (*jvm)->AttachCurrentThread(jvm, (void**)&e, NULL); detach = 1; }
        if (e) (*e)->DeleteGlobalRef(e, task->eventSink);
        if (detach) (*jvm)->DetachCurrentThread(jvm);
    }
    task->eventSink = NULL;
    task->eventMethod = NULL;

    if (task->gpuMode != 1) {
        pthread_mutex_lock(&task->frameMutex);
        free(task->frameData);
        task->frameData = NULL;
        task->frameW = 0;
        task->frameH = 0;
        task->frameReady = 0;
        pthread_mutex_unlock(&task->frameMutex);
    }

    free(task->sourceUrl);
    task->sourceUrl = NULL;
    if (task->headers) {
        for (int i = 0; i < task->nheaders; i++) free(task->headers[i]);
        free(task->headers);
        task->headers = NULL;
    }
}

/* ---- Wayland session detection ---- */
JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isWaylandSession(
    JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return detect_wayland() ? JNI_TRUE : JNI_FALSE;
}

static CreateTask *h(jlong handle) {
    return handle ? (CreateTask *)(intptr_t)handle : NULL;
}

static void letterbox_scale_rgba_to_argb(
    unsigned char *src, int srcW, int srcH, int srcStride,
    jint *dst, int dstW, int dstH)
{
    double aspect = (double)srcW / srcH;
    double dstAspect = (double)dstW / dstH;
    int drawW, drawH, offX, offY;
    if (dstAspect > aspect) {
        drawH = dstH;
        drawW = (int)(dstH * aspect + 0.5);
        offX = (dstW - drawW) / 2;
        offY = 0;
    } else {
        drawW = dstW;
        drawH = (int)(dstW / aspect + 0.5);
        offX = 0;
        offY = (dstH - drawH) / 2;
    }
    if (drawW > dstW) drawW = dstW;
    if (drawH > dstH) drawH = dstH;
    if (offX < 0) offX = 0;
    if (offY < 0) offY = 0;

    for (int y = 0; y < drawH; y++) {
        int srcY = y * srcH / drawH;
        unsigned char *row = (unsigned char *)(src + srcY * srcStride);
        for (int x = 0; x < drawW; x++) {
            int srcX = x * srcW / drawW;
            unsigned char r = row[srcX * 4 + 0];
            unsigned char g = row[srcX * 4 + 1];
            unsigned char b = row[srcX * 4 + 2];
            dst[(y + offY) * dstW + (x + offX)] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }
}

static void letterbox_scale_rgba_to_bgra(
    unsigned char *src, int srcW, int srcH, int srcStride,
    unsigned char *dst, int dstW, int dstH)
{
    double aspect = (double)srcW / srcH;
    double dstAspect = (double)dstW / dstH;
    int drawW, drawH, offX, offY;
    if (dstAspect > aspect) {
        drawH = dstH;
        drawW = (int)(dstH * aspect + 0.5);
        offX = (dstW - drawW) / 2;
        offY = 0;
    } else {
        drawW = dstW;
        drawH = (int)(dstW / aspect + 0.5);
        offX = 0;
        offY = (dstH - drawH) / 2;
    }
    if (drawW > dstW) drawW = dstW;
    if (drawH > dstH) drawH = dstH;
    if (offX < 0) offX = 0;
    if (offY < 0) offY = 0;

    memset(dst, 0, (size_t)dstW * dstH * 4);
    for (int y = 0; y < drawH; y++) {
        int srcY = y * srcH / drawH;
        unsigned char *sRow = (unsigned char *)(src + srcY * srcStride);
        unsigned char *dRow = (unsigned char *)(dst + ((y + offY) * dstW + offX) * 4);
        for (int x = 0; x < drawW; x++) {
            int srcX = x * srcW / drawW;
            dRow[x * 4 + 0] = sRow[srcX * 4 + 2]; /* B */
            dRow[x * 4 + 1] = sRow[srcX * 4 + 1]; /* G */
            dRow[x * 4 + 2] = sRow[srcX * 4 + 0]; /* R */
            dRow[x * 4 + 3] = 0xFF;                 /* A */
        }
    }
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_renderFrame(
    JNIEnv *env, jobject thiz, jlong handle,
    jintArray dstPixels, jint dstW, jint dstH) {
    (void)thiz;
    CreateTask *task = h(handle);
    if (!task || !task->alive) return JNI_FALSE;
    if (task->gpuMode == 1) return JNI_FALSE;
    if (!task->frameData) return JNI_FALSE;

    pthread_mutex_lock(&task->frameMutex);

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

    letterbox_scale_rgba_to_argb(
        (unsigned char *)src, srcW, srcH, srcStride,
        dst, dstW, dstH);

    (*env)->ReleaseIntArrayElements(env, dstPixels, dst, 0);
    pthread_mutex_unlock(&task->frameMutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_renderFrameBytes(
    JNIEnv *env, jobject thiz, jlong handle,
    jbyteArray dstBytes, jint dstW, jint dstH) {
    (void)thiz;
    CreateTask *task = h(handle);
    if (!task || !task->alive) return JNI_FALSE;
    if (task->gpuMode == 1) return JNI_FALSE;
    if (!task->frameData) return JNI_FALSE;

    pthread_mutex_lock(&task->frameMutex);

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

    letterbox_scale_rgba_to_bgra(
        (unsigned char *)src, srcW, srcH, srcStride,
        (unsigned char *)dst, dstW, dstH);

    (*env)->ReleaseByteArrayElements(env, dstBytes, dst, 0);
    pthread_mutex_unlock(&task->frameMutex);
    return JNI_TRUE;
}

/* ---- remaining JNI stubs ---- */

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(
    JNIEnv *env, jobject thiz, jlong handle, jstring json) {
    (void)env; (void)thiz; (void)handle; (void)json;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(
    JNIEnv *env, jobject thiz, jlong hdl, jboolean paused) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    mpv_set_property_string(task->mpv, "pause", paused == JNI_TRUE ? "yes" : "no");
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(
    JNIEnv *env, jobject thiz, jlong hdl, jlong posMs) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    char buf[64];
    snprintf(buf, sizeof(buf), "%f", (double)posMs / 1000.0);
    mpv_set_property_string(task->mpv, "time-pos", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(
    JNIEnv *env, jobject thiz, jlong hdl, jlong offMs) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%ld", (long)(offMs / 1000));
    const char *cmd[] = {"seek", buf, "relative", NULL};
    mpv_command_async(task->mpv, 0, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(
    JNIEnv *env, jobject thiz, jlong hdl, jfloat speed) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%f", (double)speed);
    mpv_set_property_string(task->mpv, "speed", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_adjustVolume(
    JNIEnv *env, jobject thiz, jlong hdl, jfloat delta) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    double vol = 100.0;
    mpv_get_property(task->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    vol += (double)delta;
    if (vol < 0) vol = 0;
    if (vol > 200) vol = 200;
    char buf[32];
    snprintf(buf, sizeof(buf), "%f", vol);
    mpv_set_property_string(task->mpv, "volume", buf);
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return 0.0f;
    double vol = 100.0;
    mpv_get_property(task->mpv, "volume", MPV_FORMAT_DOUBLE, &vol);
    if (vol < 0) vol = 0;
    if (vol > 200) vol = 200;
    return (jfloat)(vol / 100.0);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(
    JNIEnv *env, jobject thiz, jlong hdl, jfloat level) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    double vol = (double)level * 100.0;
    if (vol < 0) vol = 0;
    if (vol > 200) vol = 200;
    char buf[32];
    snprintf(buf, sizeof(buf), "%f", vol);
    mpv_set_property_string(task->mpv, "volume", buf);
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return 0;
    int64_t d = 0;
    mpv_get_property(task->mpv, "duration", MPV_FORMAT_INT64, &d);
    return (jlong)d * 1000;
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return 0;
    int64_t pos = 0;
    mpv_get_property(task->mpv, "time-pos", MPV_FORMAT_INT64, &pos);
    return (jlong)pos * 1000;
}

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return 0;
    int64_t pos = 0;
    mpv_get_property(task->mpv, "demuxer-cached-time", MPV_FORMAT_INT64, &pos);
    return (jlong)pos * 1000;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return JNI_FALSE;

    int idle = 1;
    mpv_get_property(task->mpv, "idle-active", MPV_FORMAT_FLAG, &idle);
    if (idle) return JNI_FALSE;

    int64_t cacheState = 0;
    mpv_get_property(task->mpv, "cache-buffering-state", MPV_FORMAT_INT64, &cacheState);
    if (cacheState > 0 && cacheState < 100) return JNI_TRUE;

    int paused = 0;
    mpv_get_property(task->mpv, "pause", MPV_FORMAT_FLAG, &paused);
    int coreIdle = 1;
    mpv_get_property(task->mpv, "core-idle", MPV_FORMAT_FLAG, &coreIdle);
    if (coreIdle && !paused) return JNI_TRUE;

    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return JNI_FALSE;
    int eof = 0;
    mpv_get_property(task->mpv, "eof-reached", MPV_FORMAT_FLAG, &eof);
    return eof ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return JNI_TRUE;
    int paused = 1;
    mpv_get_property(task->mpv, "pause", MPV_FORMAT_FLAG, &paused);
    return paused ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return 1.0f;
    char *s = mpv_get_property_string(task->mpv, "speed");
    float v = s ? (float)atof(s) : 1.0f;
    mpv_free(s);
    return v;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(
    JNIEnv *env, jobject thiz, jlong hdl, jint mode) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    const char *v = (mode == 1) ? "1.0" : (mode == 2) ? "-1.0" : "0.0";
    mpv_set_property_string(task->mpv, "panscan", v);
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return (*env)->NewStringUTF(env, "[]");
    char *json = tracks_json_for_type(task->mpv, "audio");
    jstring r = (*env)->NewStringUTF(env, json ? json : "[]");
    free(json);
    return r;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return (*env)->NewStringUTF(env, "[]");
    char *json = tracks_json_for_type(task->mpv, "sub");
    jstring r = (*env)->NewStringUTF(env, json ? json : "[]");
    free(json);
    return r;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(
    JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", id);
    mpv_set_property_string(task->mpv, "aid", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(
    JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    if (id < 0) {
        mpv_set_property_string(task->mpv, "sid", "no");
    } else {
        char buf[16];
        snprintf(buf, sizeof(buf), "%d", id);
        mpv_set_property_string(task->mpv, "sid", buf);
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(
    JNIEnv *env, jobject thiz, jlong hdl, jstring url) {
    (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    const char *u = (*env)->GetStringUTFChars(env, url, NULL);
    if (u) {
        const char *cmd[] = {"sub-add", u, "select", NULL};
        int ret = mpv_command_async(task->mpv, 0, cmd);
        if (ret < 0) {
            DBG("addSubtitleUrl: mpv_command_async failed: %s\n", mpv_error_string(ret));
        }
        (*env)->ReleaseStringUTFChars(env, url, u);
    }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(
    JNIEnv *env, jobject thiz, jlong hdl) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    const char *cmd[] = {"sub-remove", NULL};
    mpv_command_async(task->mpv, 0, cmd);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(
    JNIEnv *env, jobject thiz, jlong hdl, jint id) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    const char *cmd[] = {"sub-remove", NULL};
    mpv_command_async(task->mpv, 0, cmd);
    if (id < 0) { mpv_set_property_string(task->mpv, "sid", "no"); }
    else { char buf[16]; snprintf(buf, sizeof(buf), "%d", id); mpv_set_property_string(task->mpv, "sid", buf); }
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applyWindowChrome(
    JNIEnv *env, jobject thiz, jlong wnd, jboolean dark, jint cap, jint border, jint txt) {
    (void)env; (void)thiz; (void)wnd; (void)dark; (void)cap; (void)border; (void)txt;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setWindowBorderlessFullscreen(
    JNIEnv *env, jobject thiz, jlong hwnd, jboolean fs, jint x, jint y, jint w, jint h) {
    (void)env; (void)thiz; (void)hwnd; (void)fs; (void)x; (void)y; (void)w; (void)h;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(
    JNIEnv *env, jobject thiz, jlong hdl, jint ms) {
    (void)env; (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    char buf[16]; snprintf(buf, sizeof(buf), "%d", ms);
    mpv_set_property_string(task->mpv, "sub-delay", buf);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env, jobject thiz, jlong hdl,
    jstring textColor, jstring backgroundColor, jstring outlineColor,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos) {
    (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv) return;
    const char *tc = (*env)->GetStringUTFChars(env, textColor, NULL);
    const char *bc = (*env)->GetStringUTFChars(env, backgroundColor, NULL);
    const char *oc = (*env)->GetStringUTFChars(env, outlineColor, NULL);
    char sz[16], os[16], ps[16];
    snprintf(sz, sizeof(sz), "%f", fontSize);
    snprintf(os, sizeof(os), "%f", outlineSize);
    snprintf(ps, sizeof(ps), "%d", subPos);
    mpv_set_property_string(task->mpv, "sub-color", tc);
    mpv_set_property_string(task->mpv, "sub-back-color", bc);
    mpv_set_property_string(task->mpv, "sub-border-color", oc);
    mpv_set_property_string(task->mpv, "sub-border-size", os);
    mpv_set_property_string(task->mpv, "sub-font-size", sz);
    mpv_set_property_string(task->mpv, "sub-bold", bold ? "yes" : "no");
    mpv_set_property_string(task->mpv, "sub-pos", ps);
    (*env)->ReleaseStringUTFChars(env, textColor, tc);
    (*env)->ReleaseStringUTFChars(env, backgroundColor, bc);
    (*env)->ReleaseStringUTFChars(env, outlineColor, oc);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setProperty(
    JNIEnv *env, jobject thiz, jlong hdl, jstring name, jstring value) {
    (void)thiz;
    CreateTask *task = h(hdl);
    if (!task || !task->mpv || !name || !value) return;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    if (n && v) {
        DBG("setProperty: %s = %s\n", n, v);
        mpv_set_property_string(task->mpv, n, v);
    }
    if (n) (*env)->ReleaseStringUTFChars(env, name, n);
    if (v) (*env)->ReleaseStringUTFChars(env, value, v);
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_warmupWebView2(
    JNIEnv *env, jobject thiz, jstring ctrl) {
    (void)env; (void)thiz; (void)ctrl; return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_shutdownWebView2Warmup(
    JNIEnv *env, jobject thiz) { (void)env; (void)thiz; }

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_resizeNativeView(
    JNIEnv *env, jobject thiz, jlong hdl, jint newW, jint newH) {
    (void)env; (void)thiz; (void)hdl; (void)newW; (void)newH;
}
