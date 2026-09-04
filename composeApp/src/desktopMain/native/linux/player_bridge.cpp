// Linux player bridge for Nuvio Desktop.
//
// Phase 1: embed libmpv into the host AWT Canvas's X11 window (via mpv's
// "wid" option) and implement the playback/track/subtitle JNI surface the
// Kotlin NativePlayerBridge declares. Playback state is polled by the
// Kotlin side through the getter methods; the event sink is used only for
// the (stubbed) webview control overlay, so Phase 1 forwards nothing.
//
// Parity note: addon/debrid streams reach this bridge already resolved to
// a URL plus HTTP header lines. We forward headerLines verbatim to mpv's
// http-header-fields, exactly like the macOS/Windows bridges, so header-
// gated addons and debrid links behave identically.

#include <jni.h>
#include <mpv/client.h>

#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>
#include <X11/Xlib.h>
#include <X11/extensions/Xcomposite.h>

#include <algorithm>
#include <atomic>
#include <clocale>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <mutex>
#include <cstdlib>
#include <set>
#include <string>
#include <thread>
#include <vector>

// Diagnostic logging is opt-in via NUVIO_BRIDGE_DEBUG=1 so a normal run is quiet;
// genuine errors always log via NUVIO_ERR.
static bool nuvioDebug() {
    static const bool on = std::getenv("NUVIO_BRIDGE_DEBUG") != nullptr;
    return on;
}
// Millisecond monotonic timestamp in every line: cold-start and stall
// investigations need real deltas, and g_get_monotonic_time is cheap.
#define NUVIO_ERR(...) do { fprintf(stderr, "[nuvio-bridge %8ld] ", (long)(g_get_monotonic_time() / 1000)); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); fflush(stderr); } while (0)
#define NUVIO_LOG(...) do { if (nuvioDebug()) { NUVIO_ERR(__VA_ARGS__); } } while (0)

namespace {

JavaVM *gVm = nullptr;
constexpr double kMaxVolumePercent = 200.0;

struct Player {
    mpv_handle *mpv = nullptr;
    std::thread eventThread;
    std::atomic<bool> running{false};
    std::atomic<bool> ended{false};
    jobject eventSink = nullptr;    // global ref, JS control events dispatch here
    jmethodID eventMethod = nullptr; // onPlayerEvent(String, double)
    // Phase 2: WebKitGTK controls overlay, all touched only on the GTK thread
    GtkWidget *gtkWindow = nullptr;
    WebKitWebView *webview = nullptr;
    Window hostXid = 0;
    Window overlayXid = 0;   // controls window, composite-redirected offscreen
                             // (invisible on screen but still receives input)
    guint updateTimer = 0;    // 200ms: state push + input raise
    guint compositeTimer = 0; // fast: snapshot controls page -> mpv overlay
    bool overlayActive = true;   // controls currently visible/interacting
    int fadeTicks = 0;           // extra composite ticks to render the fade-out
    bool overlayPushed = false;  // an overlay is currently set on mpv
    // Native mirror of the page's cursor-hiding (CSS cursor:none never takes
    // effect on the redirected overlay window — see setOverlayCursorHidden).
    GdkCursor *cursorVisible = nullptr;  // "default", owned
    GdkCursor *cursorHidden = nullptr;   // blank, owned
    bool cursorIsHidden = false;
    // Async WebKit snapshot of the controls page (premultiplied ARGB32 with real
    // alpha). Reading the redirected window's X pixmap instead is renderer- and
    // driver-dependent: on NVIDIA the dmabuf renderer leaves the pixmap empty and
    // the fallback renderer fills the page background opaque, so the overlay
    // either vanishes or blacks out the video underneath.
    cairo_surface_t *snapSurf = nullptr;      // buffer mpv's overlay points at
    cairo_surface_t *snapSurfPrev = nullptr;  // kept one push longer: mpv may
                                              // still sample it mid-frame
    bool snapInFlight = false;
    int snapWaitTicks = 0;  // watchdog: ticks spent waiting on the in-flight snapshot
    // Snapshot requests are generation-stamped: a watchdog reset (or teardown)
    // bumps the generation so the abandoned request's late callback is dropped
    // instead of racing the replacement (double-clearing snapInFlight, pushing
    // overlays out of order, or rotating a surface mpv's VO still samples).
    int snapGen = 0;
    GCancellable *snapCancel = nullptr;  // cancels the in-flight snapshot, owned
    int snapCooldownTicks = 0;  // watchdog backoff before the next request
    int snapResets = 0;         // consecutive watchdog resets without a snapshot
    // Controls-state payload buffering (macOS/Windows parity): the first
    // updateControls arrives before the page defines window.playerControls, so a
    // fire-and-forget eval loses it and the loading screen shows a bare spinner
    // on black (no title/artwork) until some state change forces a resend. The
    // latest payload is kept for the player's whole life (not cleared once
    // delivered): Kotlin dedups by structure and may never resend, so this is
    // also what restores the page after a web-process crash/reload.
    std::string pendingControlsJson;
    std::atomic<bool> firstFrameShown{false};  // gates the loading-screen composite
    // Keyboard shortcuts render a small feedback toast in the page without
    // revealing the chrome, so they open the composite gate for a bounded
    // window instead of latching overlayActive (nothing would ever close it —
    // hideChrome only fires when visible chrome fades).
    int toastTicks = 0;
    // Page elements macOS/Windows show over the video while the chrome is
    // hidden (their webviews are always-visible layers). Mirrored from the
    // state JSON so the gate stays open while either is on screen.
    bool skipPromptShown = false;
    bool nextEpisodeShown = false;
    // X input focus owner before the overlay grabbed it (teardown restores it;
    // 0 = nothing saved). The overlay holds real X focus while the player is
    // attached — WKWebView/WebView2 parity — so the page's own keydown
    // shortcuts work instead of a Kotlin-side reimplementation.
    Window savedFocusXid = 0;
};

// ---- Player liveness -----------------------------------------------------
// The GTK timers (compositeTick / pushPlayerUpdate) hold a Player* and run on
// the detached GTK thread. They must never touch a Player that dispose() has
// freed, nor a half-unloaded process during exit. Guard every callback: skip if
// the process is shutting down or the Player is no longer registered as live.
std::mutex gLiveMutex;
std::set<Player *> gLivePlayers;
std::atomic<bool> gShuttingDown{false};

bool playerAlive(Player *p) {
    if (gShuttingDown.load()) return false;
    std::lock_guard<std::mutex> lk(gLiveMutex);
    return gLivePlayers.find(p) != gLivePlayers.end();
}

// ---- GTK thread ----------------------------------------------------------
// GTK is not thread-safe: it is initialised on a dedicated thread that owns
// the default main context + loop, and every GTK/WebKit call is marshalled
// there via g_main_context_invoke.

std::once_flag gGtkOnce;
std::atomic<bool> gGtkReady{false};
std::thread gGtkThread;

void gtkThreadMain() {
    // Force the X11 GDK backend. On Wayland sessions (e.g. KDE Plasma) GTK would
    // otherwise pick the Wayland backend and the controls window would be a
    // Wayland surface — but we drive it with X11/XComposite (the AWT host is an
    // XWayland X11 window), which fails with BadMatch on Composite. XWayland
    // always provides X11, so this is safe and matches GDK_BACKEND=x11.
    gdk_set_allowed_backends("x11");
    gtk_init(nullptr, nullptr);
    gGtkReady.store(true);
    gtk_main();
}

void ensureGtk() {
    std::call_once(gGtkOnce, [] {
        gGtkThread = std::thread(gtkThreadMain);
        gGtkThread.detach();
        while (!gGtkReady.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    });
}

// Escape a UTF-8 string as a JS string literal (single-quoted).
std::string jsLiteral(const std::string &s) {
    std::string out = "'";
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '\'': out += "\\'"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out.push_back(c);
        }
    }
    out.push_back('\'');
    return out;
}

void evalJs(WebKitWebView *webview, const std::string &script) {
    if (!webview) return;
    webkit_web_view_evaluate_javascript(webview, script.c_str(), -1, nullptr,
                                        nullptr, nullptr, nullptr, nullptr);
}

// Run fn on the GTK thread and block until it completes.
struct SyncCall {
    std::function<void()> fn;
    std::mutex m;
    std::condition_variable cv;
    bool done = false;
};

gboolean syncTrampoline(gpointer data) {
    auto *s = static_cast<SyncCall *>(data);
    s->fn();
    {
        std::lock_guard<std::mutex> lock(s->m);
        s->done = true;
    }
    s->cv.notify_one();
    return G_SOURCE_REMOVE;
}

void gtkSync(std::function<void()> fn) {
    if (!gGtkReady.load()) return;
    SyncCall s;
    s.fn = std::move(fn);
    g_main_context_invoke(nullptr, syncTrampoline, &s);
    std::unique_lock<std::mutex> lock(s.m);
    s.cv.wait(lock, [&] { return s.done; });
}

// ---- small helpers -------------------------------------------------------

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    // Not GetStringUTFChars: that returns Modified UTF-8 (CESU-8), where any
    // non-BMP character (emoji in an episode overview, decorative characters in
    // stream names) becomes a 6-byte surrogate-pair encoding that is NOT valid
    // UTF-8. WebKit then rejects the whole controls eval script, and since the
    // Kotlin side dedups payloads, the page can stay on defaults (no theme, no
    // episode list) for the entire session. Convert from UTF-16 like the
    // macOS/Windows bridges do.
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    jsize len = env->GetStringLength(value);
    glong written = 0;
    gchar *utf8 = g_utf16_to_utf8(reinterpret_cast<const gunichar2 *>(chars),
                                  len, nullptr, &written, nullptr);
    env->ReleaseStringChars(value, chars);
    if (!utf8) {
        NUVIO_ERR("jstringToUtf8: UTF-16 -> UTF-8 conversion failed (len=%d)", (int)len);
        return {};
    }
    std::string result(utf8, (size_t)written);
    g_free(utf8);
    return result;
}

jstring utf8ToJstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

double mpvGetDouble(mpv_handle *mpv, const char *name) {
    double out = 0.0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &out) < 0) return 0.0;
    return out;
}

int64_t mpvGetInt(mpv_handle *mpv, const char *name) {
    int64_t out = 0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_INT64, &out) < 0) return 0;
    return out;
}

bool mpvGetFlag(mpv_handle *mpv, const char *name) {
    int flag = 0;
    if (mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &flag) < 0) return false;
    return flag != 0;
}

void mpvSetFlag(mpv_handle *mpv, const char *name, bool value) {
    int flag = value ? 1 : 0;
    mpv_set_property(mpv, name, MPV_FORMAT_FLAG, &flag);
}

// "Loading" for the UI, mirroring the macOS bridge (rawLoadingWithPaused). Stays
// true through the whole file-open phase (no duration/tracks yet) so Nuvio keeps
// its loading screen up instead of revealing mpv's black frame — e.g. while a
// non-faststart MP4 fetches its moov and seeks to the resume point.
bool computeLoading(mpv_handle *mpv) {
    if (!mpv) return true;
    bool paused = mpvGetFlag(mpv, "pause");
    bool eof = mpvGetFlag(mpv, "eof-reached");
    bool idle = mpvGetFlag(mpv, "core-idle");
    bool bufferingCache = mpvGetFlag(mpv, "paused-for-cache");
    bool fileReady = mpvGetDouble(mpv, "duration") > 0.0 ||
                     mpvGetInt(mpv, "track-list/count") > 0;
    return !fileReady || (idle && !paused && !eof) || bufferingCache;
}

// Loading for the whole initial open: stay true until the FIRST FRAME is actually
// shown, so Nuvio's opening overlay (dismissed the first time isLoading is false,
// a one-way latch) survives mpv's flag flicker during open + resume-seek. After
// the first frame, fall back to computeLoading so mid-playback rebuffers still show.
bool playerLoading(Player *p) {
    if (!p || !p->mpv) return true;
    return !p->firstFrameShown.load() || computeLoading(p->mpv);
}

void mpvSetDouble(mpv_handle *mpv, const char *name, double value) {
    mpv_set_property(mpv, name, MPV_FORMAT_DOUBLE, &value);
}

// mpv http-header-fields wants a comma-separated list; commas and
// backslashes inside a header value must be backslash-escaped.
std::string joinHeaderFields(const std::vector<std::string> &headers) {
    std::string joined;
    for (size_t i = 0; i < headers.size(); ++i) {
        if (i > 0) joined.push_back(',');
        for (char c : headers[i]) {
            if (c == '\\' || c == ',') joined.push_back('\\');
            joined.push_back(c);
        }
    }
    return joined;
}

Player *asPlayer(jlong handle) { return reinterpret_cast<Player *>(handle); }

// ---- WebKitGTK controls overlay -----------------------------------------
// The controls are the SAME shared HTML page macOS/Windows use. JS talks to
// us via window.webkit.messageHandlers.player.postMessage({type,value}) — a
// WebKit convention WebKitGTK implements natively — and we push state back
// via window.playerControls()/window.playerUpdate(), identical to WKWebView.

JNIEnv *attachGtkThread() {
    JNIEnv *env = nullptr;
    if (!gVm) return nullptr;
    if (gVm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (gVm->AttachCurrentThread((void **)&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// Completion of the probed controls-state eval: 'missing' means the page has
// not defined window.playerControls yet — keep the payload pending; the page's
// controlsReady message (or the next update) retries it.
void onControlsEval(GObject *src, GAsyncResult *res, gpointer data) {
    auto *player = static_cast<Player *>(data);
    GError *err = nullptr;
    JSCValue *v = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(src), res, &err);
    if (err) {
        // A real eval failure, not the page-not-ready probe (that returns
        // 'missing' successfully). E.g. an invalid script would land here —
        // never swallow it, this is the difference between a diagnosable log
        // line and a session-long silent default theme.
        NUVIO_ERR("controls eval failed: %s", err->message);
        g_error_free(err);
    }
    if (!playerAlive(player)) {
        if (v) g_object_unref(v);
        return;
    }
    char *s = (v && jsc_value_is_string(v)) ? jsc_value_to_string(v) : nullptr;
    // Deliberately keep pendingControlsJson after a successful delivery: the
    // Kotlin side dedups by structure and may never resend, so this buffered
    // copy is the only source for a re-push after a web-process crash/reload
    // (the page's full-state merge makes re-delivery idempotent).
    if (!(s && strcmp(s, "ok") == 0)) {
        NUVIO_LOG("controls payload deferred: page not ready yet");
    }
    if (s) g_free(s);
    if (v) g_object_unref(v);
}

// Deliver the pending controls payload, probing for window.playerControls so a
// too-early push is detected and retried instead of silently short-circuited.
// GTK thread only.
void flushControlsJson(Player *player) {
    if (!player->webview || player->pendingControlsJson.empty()) return;
    std::string script =
        "(function(){if(!window.playerControls)return 'missing';"
        "window.playerControls(JSON.parse(" + jsLiteral(player->pendingControlsJson) + "));"
        "return 'ok';})()";
    webkit_web_view_evaluate_javascript(player->webview, script.c_str(), -1, nullptr,
                                        nullptr, nullptr, onControlsEval, player);
}

// Mirror the controls page's cursor-hiding natively. The page hides the cursor
// with CSS (cursor: none) when the chrome fades out, but on Linux that never
// reaches the screen: the composite-redirected overlay window pins its own
// cursor (the mutter blank-pointer workaround), the AWT canvas underneath never
// sees the pointer (the overlay is raised above it for input), and mpv's
// autohide is explicitly disabled. Swap the overlay cursor in lockstep with
// chrome visibility instead. GTK thread only.
void setOverlayCursorHidden(Player *player, bool hidden) {
    if (player->cursorIsHidden == hidden || !player->gtkWindow) return;
    GdkCursor *cur = hidden ? player->cursorHidden : player->cursorVisible;
    if (!cur) return;
    GdkWindow *gw = gtk_widget_get_window(player->gtkWindow);
    if (gw) gdk_window_set_cursor(gw, cur);
    // The WebKit view's own event window is what actually contains the pointer
    // and may define its own cursor (KWin honors it, so the toplevel's cursor is
    // never consulted there). Set it on both; WebKit may override the visible
    // cursor again on the next pointer move, which is fine — motion re-shows it.
    GdkWindow *wvw = player->webview
        ? gtk_widget_get_window(GTK_WIDGET(player->webview)) : nullptr;
    if (wvw && wvw != gw) gdk_window_set_cursor(wvw, cur);
    if (gw) gdk_display_flush(gdk_window_get_display(gw));
    player->cursorIsHidden = hidden;
    NUVIO_LOG("overlay cursor %s", hidden ? "hidden" : "shown");
}

// Give the overlay window real X input focus so the page's keydown handlers
// run — the same position WKWebView (first responder) and WebView2 (SetFocus/
// MoveFocus) hold on macOS/Windows. The overlay is composite-redirected but
// still a viewable X window, so it can own the keyboard; error-trapped because
// a not-yet-viewable window makes XSetInputFocus throw BadMatch.
void focusOverlay(Player *player) {
    if (!player->gtkWindow || !player->overlayXid) return;
    GdkWindow *gw = gtk_widget_get_window(player->gtkWindow);
    if (!gw) return;
    Display *dpy = GDK_WINDOW_XDISPLAY(gw);
    // Remember the previous focus owner (normally the AWT toplevel) so
    // teardown can hand the keyboard back. Only on the first grab: re-grabs
    // (fullscreen toggles, window refocus) must not save our own overlay.
    if (!player->savedFocusXid) {
        Window cur = 0;
        int revert = 0;
        XGetInputFocus(dpy, &cur, &revert);
        if (cur != player->overlayXid && cur != None && cur != PointerRoot) {
            player->savedFocusXid = cur;
        }
    }
    // Route GTK-side key delivery to the webkit widget once the X events arrive.
    if (player->webview) gtk_widget_grab_focus(GTK_WIDGET(player->webview));
    GdkDisplay *gdpy = gdk_window_get_display(gw);
    gdk_x11_display_error_trap_push(gdpy);
    XSetInputFocus(dpy, player->overlayXid, RevertToParent, CurrentTime);
    XFlush(dpy);
    if (gdk_x11_display_error_trap_pop(gdpy)) {
        NUVIO_ERR("XSetInputFocus overlay 0x%lx failed", player->overlayXid);
    } else {
        NUVIO_LOG("input focus -> overlay 0x%lx", player->overlayXid);
    }
}

// JS -> native: forward {type, value} to NativePlayerEventSink.onPlayerEvent.
void onPlayerMessage(WebKitUserContentManager *, WebKitJavascriptResult *js, gpointer data) {
    auto *player = static_cast<Player *>(data);
    // WebKit delivers script messages through queued main-loop IPC: one posted
    // just before close (cursorActivity fires on every mouse move) can land
    // after dispose() freed the Player. playerAlive compares the pointer value
    // against the live set — safe on a freed pointer.
    if (!playerAlive(player)) return;
    if (!player->eventSink || !player->eventMethod) return;
    JSCValue *msg = webkit_javascript_result_get_js_value(js);
    if (!msg || !jsc_value_is_object(msg)) return;
    JSCValue *typeV = jsc_value_object_get_property(msg, "type");
    JSCValue *valV = jsc_value_object_get_property(msg, "value");
    char *type = typeV ? jsc_value_to_string(typeV) : nullptr;
    double value = (valV && jsc_value_is_number(valV)) ? jsc_value_to_double(valV) : 0.0;
    // Track whether the controls chrome is on screen so we only pay the pixmap
    // readback + overlay cost while it is actually visible. hideChrome means the
    // chrome faded out; every other event (cursor/keep-visible/toggle/...) means
    // it is up. cursorActivity also arrives while hidden (mouse woke the UI) and
    // must re-activate compositing so the fade-in is actually shown.
    if (type) {
        if (strcmp(type, "setPlaybackState") == 0 ||
            strcmp(type, "setPlaybackStateQuiet") == 0) {
            bool shouldPlay = value >= 0.5;
            if (shouldPlay && (player->ended.load() || mpvGetFlag(player->mpv, "eof-reached"))) {
                const char *cmd[] = {"seek", "0", "absolute", nullptr};
                mpv_command(player->mpv, cmd);
                player->ended.store(false);
            }
            mpvSetFlag(player->mpv, "pause", !shouldPlay);
        } else if (strncmp(type, "keyboard", 8) == 0) {
            // Keyboard shortcuts (page keydown, real X focus): the page renders
            // its feedback toast without revealing the chrome, so neither latch
            // overlayActive (nothing would ever unlatch it — hideChrome only
            // fires when visible chrome fades) nor touch the cursor (a keypress
            // must not un-hide it). Composite through a bounded window covering
            // the toast's 1400ms + fade at the 33ms tick.
            player->toastTicks = 55;
        } else if (strcmp(type, "hideChrome") == 0) {
            player->overlayActive = false;
            player->fadeTicks = 18;  // keep compositing ~0.5s to render the fade-out
            // Cursor follows chrome: hidden on hideChrome, back on any activity
            // (cursorActivity also re-shows the chrome via the Kotlin side).
            setOverlayCursorHidden(player, true);
        } else {
            player->overlayActive = true;
            player->fadeTicks = 0;
            setOverlayCursorHidden(player, false);
        }
        // The page just defined its API surface — deliver any controls payload
        // that arrived before the page finished loading (macOS/Windows parity).
        if (strcmp(type, "controlsReady") == 0) flushControlsJson(player);
    }
    JNIEnv *env = attachGtkThread();
    if (env && type) {
        jstring jtype = env->NewStringUTF(type);
        env->CallVoidMethod(player->eventSink, player->eventMethod, jtype, (jdouble)value);
        env->DeleteLocalRef(jtype);
    }
    if (type) g_free(type);
    if (typeV) g_object_unref(typeV);
    if (valV) g_object_unref(valV);
}

// The overlay toplevel is a child of the AWT canvas at the X level, and player
// close races Compose's canvas teardown against dispose(): when AWT destroys
// the canvas first, X cascade-destroys the overlay's window, GDK reports it
// "unexpectedly destroyed", and GTK reacts to the external GDK_DESTROY by
// gtk_widget_destroy()ing — finalizing — the toplevel itself. Track that here
// so the queued teardown never touches the freed widget (every user of these
// fields is null-guarded).
void onOverlayDestroyed(GtkWidget *, gpointer data) {
    auto *player = static_cast<Player *>(data);
    NUVIO_LOG("overlay toplevel destroyed (host teardown or dispose)");
    player->gtkWindow = nullptr;
    player->webview = nullptr;
    player->overlayXid = 0;
}

// Free the snapshot buffers (safe to call with none allocated). Only after
// overlay-remove or teardown — mpv's overlay points into snapSurf's data.
void releaseSnapshots(Player *player) {
    if (player->snapSurf) cairo_surface_destroy(player->snapSurf);
    if (player->snapSurfPrev) cairo_surface_destroy(player->snapSurfPrev);
    player->snapSurf = player->snapSurfPrev = nullptr;
}

// Ties a snapshot request to the generation it was issued under, so callbacks
// from abandoned (watchdog-reset / torn-down) requests can be told apart from
// the live one and dropped without touching any player state.
struct SnapCtx {
    Player *player;
    int gen;
};

// Completion of the async controls snapshot: hand the premultiplied BGRA pixels
// to mpv as an OSD overlay. Runs on the GTK thread like the tick that issued it.
void onOverlaySnapshot(GObject *src, GAsyncResult *res, gpointer data) {
    auto *ctx = static_cast<SnapCtx *>(data);
    Player *player = ctx->player;
    int gen = ctx->gen;
    delete ctx;
    GError *err = nullptr;
    cairo_surface_t *surf =
        webkit_web_view_get_snapshot_finish(WEBKIT_WEB_VIEW(src), res, &err);
    if (err) g_error_free(err);
    if (!playerAlive(player)) {
        if (surf) cairo_surface_destroy(surf);
        return;
    }
    if (gen != player->snapGen) {
        // Abandoned request draining late (after a watchdog reset or a
        // cancellation). Touch nothing: clearing snapInFlight here would let
        // requests pile up concurrently, and pushing the (older) pixels would
        // rewind the overlay and rotate a surface mpv may still be sampling.
        NUVIO_LOG("stale snapshot (gen %d != %d) dropped", gen, player->snapGen);
        if (surf) cairo_surface_destroy(surf);
        return;
    }
    player->snapInFlight = false;
    player->snapResets = 0;
    if (!surf) return;
    if (cairo_image_surface_get_format(surf) != CAIRO_FORMAT_ARGB32 ||
        cairo_image_surface_get_width(surf) <= 0 ||
        cairo_image_surface_get_height(surf) <= 0 || !player->mpv) {
        cairo_surface_destroy(surf);
        return;
    }
    // Drop snapshots taken at a stale size (requested mid-resize): pushing one
    // would paint mis-scaled controls over the video. The overlay was already
    // removed when the mismatch was detected; the next tick requests a fresh
    // snapshot at the settled size.
    if (player->gtkWindow) {
        GdkWindow *ovGw = gtk_widget_get_window(player->gtkWindow);
        XWindowAttributes wa;
        if (ovGw && player->overlayXid &&
            XGetWindowAttributes(GDK_WINDOW_XDISPLAY(ovGw), player->overlayXid, &wa) &&
            (cairo_image_surface_get_width(surf) != wa.width ||
             cairo_image_surface_get_height(surf) != wa.height)) {
            cairo_surface_destroy(surf);
            return;
        }
    }
    cairo_surface_flush(surf);
    char addr[32], sw[16], sh[16], sstride[16];
    snprintf(addr, sizeof addr, "&%zu",
             (size_t)(uintptr_t)cairo_image_surface_get_data(surf));
    snprintf(sw, sizeof sw, "%d", cairo_image_surface_get_width(surf));
    snprintf(sh, sizeof sh, "%d", cairo_image_surface_get_height(surf));
    snprintf(sstride, sizeof sstride, "%d", cairo_image_surface_get_stride(surf));
    const char *cmd[] = {"overlay-add", "0", "0", "0", addr, "0",
                         "bgra", sw, sh, sstride, nullptr};
    mpv_command(player->mpv, cmd);
    player->overlayPushed = true;
    if (player->snapSurfPrev) cairo_surface_destroy(player->snapSurfPrev);
    player->snapSurfPrev = player->snapSurf;
    player->snapSurf = surf;
}

// Snapshot the controls page (premultiplied BGRA with real alpha) and hand it
// to mpv as an OSD overlay, so mpv blends the HTML controls over the video in its
// single window (XWayland won't alpha-blend sibling windows; mpv does the compose
// that Core Animation / DWM do on macOS / Windows). Only runs while the chrome is
// visible (plus a short fade-out grace) so normal watching pays nothing.
void compositeOverlay(Player *player) {
    if (!player->overlayXid || !player->mpv || !player->gtkWindow) return;
    GdkWindow *gw = gtk_widget_get_window(player->gtkWindow);
    if (!gw) return;
    Display *dpy = GDK_WINDOW_XDISPLAY(gw);
    // Also composite while loading (before the first frame, or during a rebuffer)
    // so Nuvio's loading screen — poster, title, spinner — shows over mpv's black
    // instead of a bare black screen. mpv is not decoding then, so it is free.
    bool loading = playerLoading(player);
    // Also composite while paused: the "You're watching" pause overlay only shows
    // once the chrome has hidden, which is exactly when this gate used to go
    // inactive — the page rendered it but it never reached mpv. mpv is not
    // decoding while paused, so this is free.
    bool paused = mpvGetFlag(player->mpv, "pause");
    // toastTicks: bounded window for keyboard-feedback toasts; skip prompt /
    // next-episode card: page elements shown over playing video with the chrome
    // hidden (always visible on macOS/Windows, whose webviews are real layers).
    bool active = loading || paused || player->overlayActive || player->fadeTicks > 0 ||
                  player->toastTicks > 0 || player->skipPromptShown ||
                  player->nextEpisodeShown;
    // Track the host (video) size BEFORE the activity gate: on resize/fullscreen
    // the host canvas changes size but the overlay does not, so the controls +
    // their click hit-area drift out of alignment. This must also run while the
    // chrome is hidden — a resize then would otherwise leave the (input-topmost)
    // overlay at its old, smaller size, and mouse motion in the uncovered region
    // goes to mpv's window instead: the page never sees it and moving the mouse
    // "sometimes" fails to reveal the controls.
    XWindowAttributes hostWa;
    if (XGetWindowAttributes(dpy, player->hostXid, &hostWa) && hostWa.width > 0 &&
        hostWa.height > 0) {
        XWindowAttributes ovWa0;
        if (XGetWindowAttributes(dpy, player->overlayXid, &ovWa0) &&
            (ovWa0.width != hostWa.width || ovWa0.height != hostWa.height)) {
            // gtk_window_resize alone never lands here: GTK applies it in the
            // frame-clock layout phase, and the redirected overlay's clock is
            // stalled (the same stall the forcing below works around) — on
            // mutter the sizes then mismatch forever. Resize the X window
            // directly for immediate geometry/hit-area, and keep the GTK-side
            // resize so the widget allocation follows on the forced clock tick.
            // No early return: a transiently mis-sized snapshot beats a frozen
            // overlay, and returning here would skip the clock forcing.
            // GDK sizes are logical: it multiplies by the window's scale factor
            // when it talks to X, while hostWa/ovWa0 above are X device pixels.
            // Passing device pixels here made the overlay scale-times too large
            // on a HiDPI output (3072x1782 host -> 6144x3564 overlay at scale 2),
            // so the mismatch could never settle: every tick resized, took the
            // overlay down and repushed it a tick later — a continuous
            // add/remove cycle that reads on screen as flicker.
            int ovScale = gdk_window_get_scale_factor(gw);
            if (ovScale < 1) ovScale = 1;
            const int logicalW = hostWa.width / ovScale;
            const int logicalH = hostWa.height / ovScale;
            gdk_window_resize(gw, logicalW, logicalH);
            gtk_window_resize(GTK_WINDOW(player->gtkWindow), logicalW, logicalH);
            // The X window now resizes, but the WebKit view renders at the GTK
            // widget *allocation* size, and allocations are applied in the same
            // stalled layout phase — the page would stay at the old size
            // indefinitely. Allocate synchronously so the viewport follows now.
            GtkAllocation alloc = {0, 0, logicalW, logicalH};
            gtk_widget_size_allocate(player->gtkWindow, &alloc);
            // Take the old-size overlay down while the sizes disagree: painting
            // it 1:1 over a differently-sized window garbles the controls. The
            // first snapshot at the settled size repushes it a tick later.
            if (player->overlayPushed) {
                const char *rm[] = {"overlay-remove", "0", nullptr};
                mpv_command(player->mpv, rm);
                player->overlayPushed = false;
                releaseSnapshots(player);
            }
        }
    }
    if (!active) {
        if (player->overlayPushed) {
            const char *rm[] = {"overlay-remove", "0", nullptr};
            mpv_command(player->mpv, rm);
            player->overlayPushed = false;
            releaseSnapshots(player);
        }
        return;
    }
    if (!player->snapInFlight && player->webview) {
        if (player->snapCooldownTicks > 0) {
            // Backing off after a watchdog reset: a stalled web process gets no
            // relief from being asked again immediately.
            player->snapCooldownTicks--;
        } else {
            player->snapInFlight = true;
            player->snapWaitTicks = 0;
            if (!player->snapCancel) player->snapCancel = g_cancellable_new();
            webkit_web_view_get_snapshot(player->webview, WEBKIT_SNAPSHOT_REGION_VISIBLE,
                                         WEBKIT_SNAPSHOT_OPTIONS_TRANSPARENT_BACKGROUND,
                                         player->snapCancel, onOverlaySnapshot,
                                         new SnapCtx{player, player->snapGen});
        }
    } else if (player->snapInFlight && ++player->snapWaitTicks > 30) {
        // Watchdog: a snapshot requested before the page starts loading (or after
        // a web-process death) never calls back, which would freeze the overlay
        // forever. Cancel it, invalidate its callback via the generation stamp
        // (a late arrival must not race the replacement request), and retry
        // after a short backoff.
        NUVIO_ERR("snapshot stuck in flight >30 ticks, resetting (watchdog)");
        player->snapGen++;
        if (player->snapCancel) {
            g_cancellable_cancel(player->snapCancel);
            g_object_unref(player->snapCancel);
            player->snapCancel = nullptr;
        }
        player->snapInFlight = false;
        player->snapWaitTicks = 0;
        player->snapCooldownTicks = 15;  // ~0.5s before retrying
        // Repeated resets mean the snapshot pipeline is wedged (web process hung
        // or dying): take the frozen overlay down so the stall reads as "controls
        // faded out" instead of a hung UI painted over the video.
        if (++player->snapResets >= 2 && player->overlayPushed) {
            const char *rm[] = {"overlay-remove", "0", nullptr};
            mpv_command(player->mpv, rm);
            player->overlayPushed = false;
            releaseSnapshots(player);
        }
    }
    // The redirected (invisible) overlay window is never presented, so on some
    // compositors (mutter's XWayland) its GTK frame clock stalls — freezing the
    // page's CSS fades/spinners mid-flight, so hideChrome never fires and the
    // loading screen never animates. Keep the clock ticking while we composite.
    {
        GdkWindow *ovGw = gtk_widget_get_window(player->gtkWindow);
        GdkFrameClock *fc = ovGw ? gdk_window_get_frame_clock(ovGw) : nullptr;
        if (fc) gdk_frame_clock_request_phase(fc, GDK_FRAME_CLOCK_PHASE_UPDATE);
    }
    if (!player->overlayActive && player->fadeTicks > 0) player->fadeTicks--;
    if (player->toastTicks > 0) player->toastTicks--;
}

// Fast timer: composite the controls over the video (cheap while hidden).
gboolean compositeTick(gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (!playerAlive(player)) return G_SOURCE_REMOVE;
    if (player->mpv && player->gtkWindow) compositeOverlay(player);
    return G_SOURCE_CONTINUE;
}

// JSON-escape a UTF-8 string for embedding in the track JSON we hand the
// controls webview / Kotlin decoder.
std::string jsonEscape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof(b), "\\u%04x", c);
                    o += b;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

// Read an mpv string property, trimmed; "" if unset.
std::string mpvGetStr(mpv_handle *mpv, const std::string &name) {
    char *v = mpv_get_property_string(mpv, name.c_str());
    std::string out = v ? v : "";
    if (v) mpv_free(v);
    size_t a = out.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = out.find_last_not_of(" \t\r\n");
    return out.substr(a, b - a + 1);
}

// Build the formatted track list both the controls webview and the Kotlin
// NativeMpvTrack decoder expect (macOS parity — mirrors tracksJsonForType):
// [{"index":N,"id":"..","label":"..","language":"..","selected":bool,"forced":bool}]
// (raw mpv track-list JSON does NOT match: id is an int, no index/label, lang!=language.)
std::string buildTracksJson(mpv_handle *mpv, const char *wantedType) {
    if (!mpv) return "[]";
    int64_t count = mpvGetInt(mpv, "track-list/count");
    bool isSub = std::string(wantedType) == "sub";
    bool isAudio = std::string(wantedType) == "audio";
    std::string out = "[";
    int logicalIndex = 0;
    bool first = true;
    for (int64_t i = 0; i < count; i++) {
        std::string pfx = "track-list/" + std::to_string(i);
        if (mpvGetStr(mpv, pfx + "/type") != wantedType) continue;
        int64_t id = mpvGetInt(mpv, (pfx + "/id").c_str());
        std::string title = mpvGetStr(mpv, pfx + "/title");
        std::string lang = mpvGetStr(mpv, pfx + "/lang");
        std::string codec = mpvGetStr(mpv, pfx + "/codec");
        // Clean channel-layout name. mpv names unknown layouts "unknownN" (e.g.
        // "unknown2"); map from the channel count to a friendly name instead.
        std::string channels;
        if (isAudio) {
            std::string rawCh = mpvGetStr(mpv, pfx + "/demux-channels");
            if (!rawCh.empty() && rawCh.rfind("unknown", 0) != 0) {
                channels = rawCh == "mono" ? "Mono"
                         : (rawCh == "stereo" ? "Stereo" : rawCh);
            } else {
                int64_t nch = mpvGetInt(mpv, (pfx + "/demux-channel-count").c_str());
                if (nch == 1) channels = "Mono";
                else if (nch == 2) channels = "Stereo";
                else if (nch == 6) channels = "5.1";
                else if (nch == 8) channels = "7.1";
                else if (nch > 0) channels = std::to_string(nch) + "ch";
            }
        }
        bool selected = mpvGetFlag(mpv, (pfx + "/selected").c_str());
        bool forced = mpvGetFlag(mpv, (pfx + "/forced").c_str());

        std::string base = !title.empty() ? title
                         : (!lang.empty() ? lang
                         : ((isSub ? "Subtitle " : "Track ") + std::to_string(logicalIndex + 1)));
        std::string extra;
        auto appendDetail = [&](const std::string &d) {
            if (d.empty() || d == "unknown") return;
            if (base.find(d) != std::string::npos) return;
            if (!extra.empty()) extra += ", ";
            extra += d;
        };
        if (isAudio) appendDetail(channels);
        appendDetail(codec);
        std::string label = extra.empty() ? base : base + " (" + extra + ")";

        if (!first) out += ",";
        first = false;
        out += "{\"index\":" + std::to_string(logicalIndex)
             + ",\"id\":\"" + std::to_string(id) + "\""
             + ",\"label\":\"" + jsonEscape(label) + "\""
             + ",\"language\":\"" + jsonEscape(lang) + "\""
             + ",\"selected\":" + (selected ? "true" : "false")
             + ",\"forced\":" + (forced ? "true" : "false")
             + "}";
        logicalIndex++;
    }
    out += "]";
    return out;
}

gboolean pushPlayerUpdate(gpointer data) {
    auto *player = static_cast<Player *>(data);
    if (!playerAlive(player)) return G_SOURCE_REMOVE;
    if (!player->webview || !player->mpv) return G_SOURCE_CONTINUE;
    // Keep the (redirected, invisible) overlay window topmost so pointer/click
    // events reach it instead of mpv's video window below. Redirection keeps it
    // hidden from the screen regardless of stacking; raising only affects input.
    // Only restack when NOT already topmost: an unconditional XRaiseWindow makes
    // some servers (mutter's XWayland) emit pointer crossing events every tick,
    // which the controls page reads as endless cursor activity — chrome never
    // auto-hides and hover/cursor state thrashes.
    if (player->gtkWindow) {
        GdkWindow *ov = gtk_widget_get_window(player->gtkWindow);
        if (ov) {
            Display *dpy = GDK_WINDOW_XDISPLAY(ov);
            Window root0, parent0, *kids = nullptr;
            unsigned int nkids = 0;
            bool onTop = false;
            if (XQueryTree(dpy, player->hostXid, &root0, &parent0, &kids, &nkids) &&
                kids) {
                onTop = nkids > 0 && kids[nkids - 1] == player->overlayXid;
                XFree(kids);
            }
            if (!onTop) XRaiseWindow(dpy, player->overlayXid);
        }
    }
    double duration = mpvGetDouble(player->mpv, "duration");
    double position = mpvGetDouble(player->mpv, "time-pos");
    double volumeLevel = mpvGetDouble(player->mpv, "volume") / 100.0;
    volumeLevel = std::max(0.0, std::min(kMaxVolumePercent / 100.0, volumeLevel));
    bool paused = mpvGetFlag(player->mpv, "pause");
    bool loading = playerLoading(player);
    std::string audioTracks = buildTracksJson(player->mpv, "audio");
    std::string subtitleTracks = buildTracksJson(player->mpv, "sub");
    char head[224];
    snprintf(head, sizeof(head),
             "window.playerUpdate&&window.playerUpdate({duration:%0.3f,position:%0.3f,volumeLevel:%0.3f,paused:%s,loading:%s,audioTracks:",
             duration, position, volumeLevel, paused ? "true" : "false", loading ? "true" : "false");
    std::string js = std::string(head) + audioTracks +
                     ",subtitleTracks:" + subtitleTracks + "})";
    evalJs(player->webview, js);
    return G_SOURCE_CONTINUE;
}

struct WebviewSetup {
    Player *player;
    Window hostXid;
    std::string url;
};

// ---- Webview warm-up (adoption model) -------------------------------------
// The warm view is not a throwaway probe (the Windows semantics): the next
// player ADOPTS it — window, view, and user-content manager — so the controls
// page is already parsed and rendering when the player opens, and the loading
// screen composites within ~150ms instead of after a full engine+page load.
// After adoption a fresh warm view is spawned in the background for the next
// open. All GTK-thread owned.
GtkWidget *gWarmupWindow = nullptr;
WebKitWebView *gWarmupView = nullptr;
WebKitUserContentManager *gWarmupUcm = nullptr;
std::string gWarmupUrl;
std::mutex gWarmupMutex;
std::condition_variable gWarmupCv;
bool gWarmupStarted = false;
bool gWarmupDone = false;
bool gWarmupSucceeded = false;

void notifyWarmupDone(bool ok) {
    {
        std::lock_guard<std::mutex> lock(gWarmupMutex);
        if (gWarmupDone) return;
        gWarmupDone = true;
        gWarmupSucceeded = ok;
    }
    gWarmupCv.notify_all();
}

// One web context shared by every controls webview, with a real HTTP disk
// cache. The page re-fetches the opening artwork on every player open; with
// the per-player ephemeral context that meant a network round-trip each time —
// the gray spinner-and-bar phase of the loading screen. A shared cached
// context serves it from disk after the first view of a title. The context is
// intentionally immortal (static ref, never unref'd): the original reason for
// ephemeral contexts was a disk-backed WebKitWebsiteDataStore whose finalize()
// races the detached gtk_main thread at process exit (SIGABRT); a context
// that is never finalized cannot race.
WebKitWebContext *sharedControlsContext() {
    static WebKitWebContext *ctx = nullptr;
    if (!ctx) {
        gchar *cacheDir = g_build_filename(g_get_user_cache_dir(), "nuvio",
                                           "webkit-cache", nullptr);
        gchar *dataDir = g_build_filename(g_get_user_cache_dir(), "nuvio",
                                          "webkit-data", nullptr);
        WebKitWebsiteDataManager *dm = webkit_website_data_manager_new(
            "base-cache-directory", cacheDir,
            "base-data-directory", dataDir,
            nullptr);
        ctx = webkit_web_context_new_with_website_data_manager(dm);
        g_object_unref(dm);  // ctx holds its own ref
        g_free(cacheDir);
        g_free(dataDir);
    }
    return ctx;
}

gboolean warmupOnGtk(gpointer data);  // defined after the player pipeline

// Surface controls-page load progress (debug only) so a blank/erroring page is
// diagnosable; failures always log via onLoadFailed.
void onLoadChanged(WebKitWebView * /*wv*/, WebKitLoadEvent event, gpointer data) {
    const char *name = event == WEBKIT_LOAD_STARTED ? "started"
                     : event == WEBKIT_LOAD_REDIRECTED ? "redirected"
                     : event == WEBKIT_LOAD_COMMITTED ? "committed"
                     : event == WEBKIT_LOAD_FINISHED ? "finished"
                     : "unknown";
    NUVIO_LOG("webview load-changed: %s", name);
    // Belt-and-braces alongside the controlsReady message: retry the pending
    // controls payload once the page finishes loading (the probe re-defers it
    // if scripts haven't defined window.playerControls yet).
    auto *player = static_cast<Player *>(data);
    if (event == WEBKIT_LOAD_FINISHED && playerAlive(player)) flushControlsJson(player);
}

gboolean onLoadFailed(WebKitWebView * /*wv*/, WebKitLoadEvent /*event*/,
                      gchar *uri, GError *error, gpointer /*data*/) {
    NUVIO_ERR("webview load-FAILED uri=%s error=%s", uri ? uri : "(null)",
              error ? error->message : "(null)");
    return FALSE;
}

// Runs on the GTK thread: build a transparent WebKitGTK window, reparent it
// as a child of the host AWT/X11 window (over the mpv video), load the
// controls page, and start the state-push timer.
gboolean createWebviewOnGtk(gpointer data) {
    auto *s = static_cast<WebviewSetup *>(data);
    Player *player = s->player;

    // Fast open->close: dispose() can free the player before this queued call
    // runs. Its teardown is queued behind us (FIFO), so bailing here leaves
    // nothing for it to clean up.
    if (!playerAlive(player)) {
        delete s;
        return G_SOURCE_REMOVE;
    }

    GtkWidget *win;
    WebKitWebView *wv;
    bool adopted = false;

    if (gWarmupWindow && gWarmupView && gWarmupUcm) {
        // Adopt the warm view: the controls page is already parsed (or well
        // into loading), so the loading screen can composite almost
        // immediately instead of after a full engine + page cold start. The
        // warm structure was built with identical window/visual/settings.
        win = gWarmupWindow;
        wv = gWarmupView;
        g_signal_connect(gWarmupUcm, "script-message-received::player",
                         G_CALLBACK(onPlayerMessage), player);
        gWarmupWindow = nullptr;
        gWarmupView = nullptr;
        gWarmupUcm = nullptr;
        adopted = true;
        NUVIO_LOG("adopted warm webview");
    } else {
        win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        gtk_window_set_decorated(GTK_WINDOW(win), FALSE);
        gtk_widget_set_app_paintable(win, TRUE);
        GdkScreen *screen = gtk_widget_get_screen(win);
        GdkVisual *rgba = gdk_screen_get_rgba_visual(screen);
        if (rgba) gtk_widget_set_visual(win, rgba);

        WebKitUserContentManager *ucm = webkit_user_content_manager_new();
        webkit_user_content_manager_register_script_message_handler(ucm, "player");
        g_signal_connect(ucm, "script-message-received::player",
                         G_CALLBACK(onPlayerMessage), player);

        // WebKit's DMABUF renderer yields controls snapshots with degraded alpha —
        // the semi-transparent chrome scrim reads back near-opaque (NVIDIA: stale or
        // fully opaque via GBM failures; Mesa: no fully-transparent pixels at all),
        // which mpv then blends as a dark wall over the video. The software path
        // snapshots with correct alpha everywhere, and the controls page is cheap to
        // render, so disable DMABUF before WebKit's processes spawn. overwrite=0
        // keeps an explicit user setting authoritative.
        setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 0);
        // The persistent cached context (sharedControlsContext) brings WebKit's
        // accelerated compositing up on some stacks (observed on virtio/GNOME)
        // where the DMABUF opt-out alone no longer guarantees alpha-correct
        // snapshots — same dark-wall-over-video failure as above. Force the
        // full software path; the controls page is cheap to render.
        setenv("WEBKIT_DISABLE_COMPOSITING_MODE", "1", 0);

        // Shared immortal cached context — see sharedControlsContext() for why
        // this replaced the per-player ephemeral context (artwork re-fetch on
        // every open) and how it avoids the exit-time finalize SIGABRT.
        wv = WEBKIT_WEB_VIEW(g_object_new(
            WEBKIT_TYPE_WEB_VIEW,
            "web-context", sharedControlsContext(),
            "user-content-manager", ucm,
            nullptr));
        GdkRGBA transparent = {0.0, 0.0, 0.0, 0.0};
        webkit_web_view_set_background_color(wv, &transparent);

        // The controls page is loaded from file:// and its JS pulls sibling assets
        // (js/css/fonts) plus talks to native; without file-access + console piping a
        // JS failure is silent. Mirror the capabilities the macOS/Windows webviews grant.
        WebKitSettings *settings = webkit_web_view_get_settings(wv);
        webkit_settings_set_enable_write_console_messages_to_stdout(settings, TRUE);
        webkit_settings_set_allow_file_access_from_file_urls(settings, TRUE);
        webkit_settings_set_allow_universal_access_from_file_urls(settings, TRUE);
        webkit_settings_set_enable_developer_extras(settings, TRUE);
        webkit_settings_set_javascript_can_access_clipboard(settings, TRUE);
    }

    g_signal_connect(wv, "load-changed", G_CALLBACK(onLoadChanged), player);
    g_signal_connect(wv, "load-failed", G_CALLBACK(onLoadFailed), nullptr);
    // A web-process death (crash, OOM kill) silently blanks the page: pending
    // snapshot requests never complete (the watchdog would fire forever) and the
    // controls never come back. Reload the page and drop the dead process's
    // in-flight snapshot; the buffered controls payload is re-delivered on
    // LOAD_FINISHED / controlsReady, restoring theme + metadata.
    g_signal_connect(wv, "web-process-terminated",
                     G_CALLBACK(+[](WebKitWebView *view,
                                    WebKitWebProcessTerminationReason reason,
                                    gpointer data) {
                         auto *p = static_cast<Player *>(data);
                         NUVIO_ERR("controls web process terminated (reason=%d); reloading",
                                   (int)reason);
                         if (!playerAlive(p)) return;
                         p->snapGen++;
                         if (p->snapCancel) {
                             g_cancellable_cancel(p->snapCancel);
                             g_object_unref(p->snapCancel);
                             p->snapCancel = nullptr;
                         }
                         p->snapInFlight = false;
                         p->snapWaitTicks = 0;
                         p->snapCooldownTicks = 0;
                         p->snapResets = 0;
                         webkit_web_view_reload(view);
                     }),
                     player);
    // Suppress WebKit's own right-click context menu over the controls page
    // (the macOS/Windows player webviews never show one).
    g_signal_connect(wv, "context-menu",
                     G_CALLBACK(+[](WebKitWebView *, WebKitContextMenu *,
                                    GdkEvent *, WebKitHitTestResult *,
                                    gpointer) -> gboolean { return TRUE; }),
                     nullptr);

    // Mouse thumb buttons never reach the controls page on Linux. X11 delivers
    // them as buttons 8 and 9, but the DOM numbers them 3 and 4, and WebKitGTK
    // does not translate between the two -- so the page's back/forward seek
    // handler (which is shared with Windows, where WebView2 does translate)
    // never fires here. Nothing reports it: the buttons are simply dead.
    // Emit the same events the page would have sent, so both platforms end up
    // in the same Kotlin handler.
    g_signal_connect(wv, "button-press-event",
                     G_CALLBACK(+[](GtkWidget *, GdkEventButton *ev,
                                    gpointer data) -> gboolean {
                         auto *p = static_cast<Player *>(data);
                         if (!playerAlive(p)) return FALSE;
                         const char *action = ev->button == 8   ? "seekBack"
                                              : ev->button == 9 ? "seekForward"
                                                                : nullptr;
                         if (!action) return FALSE;
                         // Consume it either way: letting a half-handled thumb
                         // button fall through to WebKit gains nothing.
                         if (!p->eventSink || !p->eventMethod) return TRUE;
                         JNIEnv *env = attachGtkThread();
                         if (env) {
                             jstring jtype = env->NewStringUTF(action);
                             env->CallVoidMethod(p->eventSink, p->eventMethod,
                                                 jtype, (jdouble)0.0);
                             env->DeleteLocalRef(jtype);
                         }
                         NUVIO_LOG("thumb button %u -> %s", ev->button, action);
                         return TRUE;
                     }),
                     player);

    if (!adopted) gtk_container_add(GTK_CONTAINER(win), GTK_WIDGET(wv));

    // Make sure the overlay actually asks the X server for pointer/keyboard
    // events; without an explicit mask WebKit gets no DOM pointer events once the
    // window is a child of a foreign (AWT) parent.
    gtk_widget_add_events(win,
                          GDK_POINTER_MOTION_MASK | GDK_BUTTON_PRESS_MASK |
                          GDK_BUTTON_RELEASE_MASK | GDK_SCROLL_MASK |
                          GDK_ENTER_NOTIFY_MASK | GDK_LEAVE_NOTIFY_MASK |
                          GDK_KEY_PRESS_MASK | GDK_KEY_RELEASE_MASK |
                          GDK_FOCUS_CHANGE_MASK);
    gtk_widget_realize(win);
    GdkWindow *gdkWin = gtk_widget_get_window(win);
    Display *dpy = GDK_WINDOW_XDISPLAY(gdkWin);
    Window gtkXid = GDK_WINDOW_XID(gdkWin);

    // size to the host window (X device pixels -> GDK logical pixels, so the
    // overlay comes up at the host's size on a HiDPI output instead of
    // scale-times too large; see the resize in compositeOverlay)
    XWindowAttributes attrs;
    if (XGetWindowAttributes(dpy, s->hostXid, &attrs)) {
        int initScale = gdk_window_get_scale_factor(gdkWin);
        if (initScale < 1) initScale = 1;
        gtk_window_resize(GTK_WINDOW(win), attrs.width / initScale, attrs.height / initScale);
    }

    // Reparent THROUGH GDK (not raw XReparentWindow): GDK must know the window is
    // now a child of the host, otherwise it never dispatches pointer events to it
    // and the controls page receives no mousemove -> chrome never shows.
    GdkDisplay *gdkDisplay = gdk_window_get_display(gdkWin);
    GdkWindow *hostGdk = gdk_x11_window_foreign_new_for_display(gdkDisplay, s->hostXid);
    if (hostGdk) {
        gdk_window_reparent(gdkWin, hostGdk, 0, 0);
    } else {
        NUVIO_ERR("foreign host GdkWindow wrap failed; falling back to XReparentWindow");
        XReparentWindow(dpy, gtkXid, s->hostXid, 0, 0);
    }
    gtk_widget_show_all(win);
    gdk_window_raise(gdkWin);

    // FEASIBILITY TEST: redirect the overlay window offscreen via the Composite
    // extension. If this hides it from the screen (revealing the video below)
    // while it keeps rendering + receiving input, we can read its pixmap and
    // blend it over the video via mpv overlay-add (no window-stacking blend).
    int compEventBase = 0, compErrorBase = 0;
    if (XCompositeQueryExtension(dpy, &compEventBase, &compErrorBase)) {
        int major = 0, minor = 0;
        XCompositeQueryVersion(dpy, &major, &minor);
        XCompositeRedirectWindow(dpy, gtkXid, CompositeRedirectManual);
        player->overlayXid = gtkXid;
        NUVIO_LOG("XComposite %d.%d present; redirected overlay 0x%lx (manual)",
                  major, minor, gtkXid);
    } else {
        NUVIO_ERR("XComposite NOT available");
    }
    // Motion events are compressed by default: GDK defers them to the frame
    // clock's flush phase. mutter implements X frame-sync, and this redirected
    // window is never presented, so that flush (almost) never runs — motion
    // events pile up undelivered and the page never sees pointer movement
    // (buttons/crossings are not compressed, which is why clicks still worked).
    // KWin has no frame-sync, so the clock free-runs and KDE never showed this.
    gdk_window_set_event_compression(gdkWin, FALSE);
    {
        GdkWindow *wvWin = gtk_widget_get_window(GTK_WIDGET(wv));
        if (wvWin && wvWin != gdkWin) gdk_window_set_event_compression(wvWin, FALSE);
    }

    // Give the overlay window a real cursor: it defines none of its own, and on
    // some WMs (mutter) the pointer goes blank over it instead of inheriting.
    // Keep a blank one alongside so chrome-hide can hide the cursor natively
    // (the page's CSS cursor:none never propagates through this window).
    {
        GdkDisplay *gdpy = gtk_widget_get_display(win);
        player->cursorVisible = gdk_cursor_new_from_name(gdpy, "default");
        player->cursorHidden = gdk_cursor_new_from_name(gdpy, "none");
        if (!player->cursorHidden)
            player->cursorHidden = gdk_cursor_new_for_display(gdpy, GDK_BLANK_CURSOR);
        if (player->cursorVisible && gdkWin)
            gdk_window_set_cursor(gdkWin, player->cursorVisible);
    }
    XFlush(dpy);

    // Adopted views are already loading (or loaded); a reload here would throw
    // away exactly the head start adoption exists for.
    if (!adopted) webkit_web_view_load_uri(wv, s->url.c_str());

    player->gtkWindow = win;
    player->webview = wv;
    player->hostXid = s->hostXid;
    g_signal_connect(win, "destroy", G_CALLBACK(onOverlayDestroyed), player);
    player->updateTimer = g_timeout_add(200, pushPlayerUpdate, player);
    player->compositeTimer = g_timeout_add(33, compositeTick, player);  // ~30fps when active

    // Grab the keyboard for the page from the start (macOS grabs first-responder
    // at creation, Windows MoveFocus()es on controller setup) — the page's own
    // keydown handler is the only keyboard path, there is no Kotlin fallback.
    focusOverlay(player);

    // Adoption consumed the warm view — spawn the next one in the background
    // so the following player open is warm too.
    if (adopted && !gWarmupUrl.empty()) {
        warmupOnGtk(new std::string(gWarmupUrl));
    }

    NUVIO_LOG("webview created + reparented into host 0x%lx", s->hostXid);
    delete s;
    return G_SOURCE_REMOVE;
}

struct ControlsUpdate {
    Player *player;
    std::string json;
};

// Parse a boolean field from the (trusted, locally-built) state JSON; fallback
// when the key is absent.
bool jsonFlag(const std::string &json, const char *key, bool fallback) {
    std::string needle = std::string("\"") + key + "\":";
    size_t k = json.find(needle);
    if (k == std::string::npos) return fallback;
    const char *v = json.c_str() + k + needle.size();
    while (*v == ' ') ++v;
    return *v == 't';
}

// native -> JS: push a fresh controls state (identical call to WKWebView).
// Buffered on the Player and delivered via the probing flush so a payload that
// arrives before the page loads is retried instead of lost.
gboolean applyControlsOnGtk(gpointer data) {
    auto *u = static_cast<ControlsUpdate *>(data);
    if (playerAlive(u->player)) {
        // Chrome visibility travels authoritatively in this state JSON. The
        // page's hideChrome message only covers its own idle-timer hides —
        // fullscreen hides are decided on the Kotlin side and arrive here as a
        // controlsVisible=false push, so mirror the cursor from the state too.
        size_t k = u->json.find("\"controlsVisible\":");
        if (k != std::string::npos) {
            const char *v = u->json.c_str() + k + strlen("\"controlsVisible\":");
            while (*v == ' ') ++v;
            bool visible = *v == 't';
            setOverlayCursorHidden(u->player, !visible);
            // Drive the composite gate from the same authoritative flag, in both
            // directions. Without this: (a) a Kotlin-initiated show (keyboard
            // command — the webview never holds keyboard focus on Linux) renders
            // chrome the page never announces via a message, so it was drawn but
            // never composited (invisible, and waving the mouse can't fix it —
            // the page already believes the chrome is up and sends nothing);
            // (b) a click-hide (toggleChrome) reaches onPlayerMessage as a
            // non-hideChrome message, so overlayActive stayed true and the
            // full-rate snapshot loop kept compositing fully-transparent frames
            // over playing video for the rest of the session.
            if (visible) {
                u->player->overlayActive = true;
                u->player->fadeTicks = 0;
            } else if (u->player->overlayActive) {
                u->player->overlayActive = false;
                u->player->fadeTicks = 18;  // render the fade-out, like hideChrome
            }
        }
        // Skip-intro prompt and next-episode card show over playing video with
        // the chrome hidden (macOS/Windows get this for free from their
        // always-visible webviews); mirror their visibility so the composite
        // gate keeps them on screen. Dismissed skip prompts render nothing —
        // don't hold the gate open for the rest of the interval.
        u->player->skipPromptShown = jsonFlag(u->json, "skipPromptVisible", false) &&
                                     !jsonFlag(u->json, "skipPromptDismissed", false);
        u->player->nextEpisodeShown = jsonFlag(u->json, "nextEpisodeVisible", false);
        u->player->pendingControlsJson = std::move(u->json);
        flushControlsJson(u->player);
    }
    delete u;
    return G_SOURCE_REMOVE;
}

// Tear the webview down on the GTK thread (owns all GTK/WebKit state).
gboolean destroyWebviewOnGtk(gpointer data) {
    auto *player = static_cast<Player *>(data);
    // The ticks self-remove once dispose() un-registers the player (they see
    // !playerAlive and return G_SOURCE_REMOVE), so by the time this teardown
    // runs the stored ID may already be dead — removing it again is the GLib
    // "Source ID not found" CRITICAL. Only remove sources GLib still tracks.
    auto removeSource = [](guint id) {
        GSource *src = g_main_context_find_source_by_id(nullptr, id);
        if (src && !g_source_is_destroyed(src)) g_source_remove(id);
    };
    if (player->updateTimer) {
        removeSource(player->updateTimer);
        player->updateTimer = 0;
    }
    if (player->compositeTimer) {
        removeSource(player->compositeTimer);
        player->compositeTimer = 0;
    }
    player->snapGen++;  // drop any in-flight snapshot callback
    if (player->snapCancel) {
        g_cancellable_cancel(player->snapCancel);
        g_object_unref(player->snapCancel);
        player->snapCancel = nullptr;
    }
    if (player->gtkWindow) {
        // Hand the keyboard back before the overlay dies — but only if we still
        // hold it (the user may have alt-tabbed elsewhere; stealing focus on
        // close would be hostile). Falls back to the host window when the saved
        // owner is gone; RevertToParent covers the rest.
        GdkWindow *gw = gtk_widget_get_window(player->gtkWindow);
        if (gw && player->overlayXid) {
            Display *dpy = GDK_WINDOW_XDISPLAY(gw);
            Window cur = 0;
            int revert = 0;
            XGetInputFocus(dpy, &cur, &revert);
            if (cur == player->overlayXid) {
                GdkDisplay *gdpy = gdk_window_get_display(gw);
                Window target = player->savedFocusXid ? player->savedFocusXid
                                                      : player->hostXid;
                gdk_x11_display_error_trap_push(gdpy);
                XSetInputFocus(dpy, target, RevertToParent, CurrentTime);
                XFlush(dpy);
                if (gdk_x11_display_error_trap_pop(gdpy) && target != player->hostXid) {
                    gdk_x11_display_error_trap_push(gdpy);
                    XSetInputFocus(dpy, player->hostXid, RevertToParent, CurrentTime);
                    XFlush(dpy);
                    gdk_x11_display_error_trap_pop_ignored(gdpy);
                }
                NUVIO_LOG("input focus restored to 0x%lx", target);
            }
        }
        player->savedFocusXid = 0;
        // WebKit's dispose resets the tooltip (WebPageProxy::close ->
        // resetState -> setToolTip("")), and GTK answers that with a
        // pointer-position query — gtk_tooltip_trigger_tooltip_query ->
        // gdk_device_get_window_at_position -> XIQueryPointer — which walks the
        // very X tree this destroy is dismantling. The reply comes back
        // BadWindow, and GDK escalates an untrapped X error to a fatal g_log,
        // i.e. G_BREAKPOINT: the process dies on int3 with no Java stack. Seen
        // at every episode boundary, where the next-episode swap disposes the
        // player. Trap the destroy the same way the focus handover above does.
        GdkDisplay *destroyDisplay = gtk_widget_get_display(player->gtkWindow);
        gdk_x11_display_error_trap_push(destroyDisplay);
        gtk_widget_destroy(player->gtkWindow);
        gdk_x11_display_error_trap_pop_ignored(destroyDisplay);
        player->gtkWindow = nullptr;
        player->webview = nullptr;
        player->overlayXid = 0;
    }
    // Outside the gtkWindow block: when the toplevel died externally (see
    // onOverlayDestroyed) the block above is skipped, but the snapshot
    // surfaces still need freeing.
    releaseSnapshots(player);
    if (player->cursorVisible) {
        g_object_unref(player->cursorVisible);
        player->cursorVisible = nullptr;
    }
    if (player->cursorHidden) {
        g_object_unref(player->cursorHidden);
        player->cursorHidden = nullptr;
    }
    return G_SOURCE_REMOVE;
}

// ---- Webview warm-up (Windows warmupWebView2 parity) ----------------------
// The first WebKitGTK view pays the whole engine cold start — mapping and
// relocating the WebKit libraries, spawning web/network processes, fontconfig
// caches, parsing the controls page. Same shape as the Windows warm-up: a
// hidden throwaway view loads the controls page once at app start and is kept
// alive until shutdown; it is never handed to a player.
gboolean warmupOnGtk(gpointer data) {
    auto *url = static_cast<std::string *>(data);
    // The warm-up spawns WebKit's processes first — the DMABUF and compositing
    // opt-outs must already be in place (see createWebviewOnGtk for why).
    setenv("WEBKIT_DISABLE_DMABUF_RENDERER", "1", 0);
    setenv("WEBKIT_DISABLE_COMPOSITING_MODE", "1", 0);
    // Built exactly like the player overlay window (createWebviewOnGtk), never
    // shown: WebKit loads and lays the page out unmapped, and adoption picks
    // the whole structure up mid-flight.
    GtkWidget *win = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_decorated(GTK_WINDOW(win), FALSE);
    gtk_widget_set_app_paintable(win, TRUE);
    GdkScreen *screen = gtk_widget_get_screen(win);
    GdkVisual *rgba = gdk_screen_get_rgba_visual(screen);
    if (rgba) gtk_widget_set_visual(win, rgba);

    WebKitUserContentManager *ucm = webkit_user_content_manager_new();
    webkit_user_content_manager_register_script_message_handler(ucm, "player");
    // controlsReady = page scripts executed end to end (same readiness signal
    // the Windows warm-up waits for). One-shot latched; harmless after
    // adoption when the player's own handler is connected alongside.
    g_signal_connect(ucm, "script-message-received::player",
                     G_CALLBACK(+[](WebKitUserContentManager *,
                                    WebKitJavascriptResult *js, gpointer) {
                         JSCValue *msg = webkit_javascript_result_get_js_value(js);
                         if (!msg || !jsc_value_is_object(msg)) return;
                         JSCValue *typeV = jsc_value_object_get_property(msg, "type");
                         char *type = typeV ? jsc_value_to_string(typeV) : nullptr;
                         if (type && strcmp(type, "controlsReady") == 0) {
                             notifyWarmupDone(true);
                         }
                         if (type) g_free(type);
                         if (typeV) g_object_unref(typeV);
                     }),
                     nullptr);
    WebKitWebView *wv = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW,
                                                     "web-context", sharedControlsContext(),
                                                     "user-content-manager", ucm,
                                                     nullptr));
    GdkRGBA transparent = {0.0, 0.0, 0.0, 0.0};
    webkit_web_view_set_background_color(wv, &transparent);
    // Full player-parity settings — this view becomes the player's overlay.
    WebKitSettings *settings = webkit_web_view_get_settings(wv);
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, TRUE);
    webkit_settings_set_allow_file_access_from_file_urls(settings, TRUE);
    webkit_settings_set_allow_universal_access_from_file_urls(settings, TRUE);
    webkit_settings_set_enable_developer_extras(settings, TRUE);
    webkit_settings_set_javascript_can_access_clipboard(settings, TRUE);
    g_signal_connect(wv, "load-failed",
                     G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent, gchar *,
                                    GError *, gpointer) -> gboolean {
                         notifyWarmupDone(false);
                         return FALSE;
                     }),
                     nullptr);
    g_signal_connect(wv, "load-changed",
                     G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent event, gpointer) {
                         if (event == WEBKIT_LOAD_FINISHED) notifyWarmupDone(true);
                     }),
                     nullptr);
    gtk_container_add(GTK_CONTAINER(win), GTK_WIDGET(wv));
    webkit_web_view_load_uri(wv, url->c_str());
    gWarmupWindow = win;
    gWarmupView = wv;
    gWarmupUcm = ucm;
    gWarmupUrl = *url;
    NUVIO_LOG("warm webview spawned (unmapped, loading %s)", url->c_str());
    delete url;
    return G_SOURCE_REMOVE;
}

// Drains the mpv event queue so the core keeps running and tracks EOF.
void runEventLoop(Player *player) {
    while (player->running.load()) {
        mpv_event *event = mpv_wait_event(player->mpv, 0.05);
        if (!event || event->event_id == MPV_EVENT_NONE) continue;
        switch (event->event_id) {
            case MPV_EVENT_LOG_MESSAGE: {
                auto *msg = static_cast<mpv_event_log_message *>(event->data);
                if (msg) NUVIO_LOG("mpv[%s] %s: %s", msg->level, msg->prefix, msg->text);
                break;
            }
            case MPV_EVENT_END_FILE: {
                auto *end = static_cast<mpv_event_end_file *>(event->data);
                if (end && end->reason == MPV_END_FILE_REASON_EOF) {
                    player->ended.store(true);
                }
                break;
            }
            case MPV_EVENT_START_FILE:
                player->ended.store(false);
                player->firstFrameShown.store(false);  // re-show loading for the new file
                break;
            case MPV_EVENT_PLAYBACK_RESTART:
                player->firstFrameShown.store(true);
                break;
            case MPV_EVENT_SHUTDOWN:
                player->running.store(false);
                break;
            default:
                break;
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    // On process exit the detached GTK thread keeps running gtk_main and firing
    // timers while libraries unload; flag shutdown so those callbacks bail.
    std::atexit([] { gShuttingDown.store(true); });
    return JNI_VERSION_1_6;
}

extern "C" {

#define NP(name) \
    Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_##name

// Initialize GTK before AWT/Compose/Skia (called as the first statement of
// main() on Linux). On some JDK builds (Ubuntu's OpenJDK) AWT/Skiko partially
// loads libgdk-3 without a full GTK init, registering GdkDisplayManager in a
// state that makes our later gtk_init on the bridge thread abort with a GType
// conflict. Initializing GTK first, on the JVM main thread, registers the GDK
// types once and canonically; the bridge thread's gtk_init then no-ops.
// Approach from skoruppa's linux-webkitgtk branch (initGtkEarly).
JNIEXPORT jboolean JNICALL NP(initGtkEarly)(JNIEnv *, jobject) {
    // Same backend pin as gtkThreadMain: the controls overlay is driven with
    // X11/XComposite, so GTK must not come up on the Wayland backend here.
    gdk_set_allowed_backends("x11");
    int argc = 0;
    if (!gtk_init_check(&argc, nullptr)) {
        NUVIO_ERR("initGtkEarly: gtk_init_check failed");
        return JNI_FALSE;
    }
    NUVIO_LOG("initGtkEarly: GTK initialized before AWT/Skia");
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL NP(create)(
    JNIEnv *env, jobject /*thiz*/, jlong hostViewPtr, jstring sourceUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jstring controlsPageUrl, jint decoderPriority,
    jboolean /*nvidiaRtxSuperResolutionEnabled*/, jobject eventSink) {

    // libmpv requires LC_NUMERIC=C (e.g. non-"C" locales with comma
    // decimals make mpv_create fail); the JVM uses java.util.Locale, so
    // this C-level change does not affect Java number formatting.
    setlocale(LC_NUMERIC, "C");

    auto *player = new Player();
    player->mpv = mpv_create();
    if (!player->mpv) {
        NUVIO_ERR("mpv_create() returned NULL");
        delete player;
        return 0;
    }
    // Config shared by both init attempts (see below).
    std::string wid;
    if (hostViewPtr != 0) {
        wid = std::to_string(static_cast<int64_t>(hostViewPtr));
        NUVIO_LOG("embedding into X11 wid=%s", wid.c_str());
    } else {
        NUVIO_ERR("hostViewPtr is 0 — no window to embed into");
    }

    // Forward addon/debrid HTTP headers verbatim.
    std::string headerFields;
    if (headerLines != nullptr) {
        jsize count = env->GetArrayLength(headerLines);
        std::vector<std::string> headers;
        headers.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto line = static_cast<jstring>(env->GetObjectArrayElement(headerLines, i));
            headers.push_back(jstringToUtf8(env, line));
            if (line) env->DeleteLocalRef(line);
        }
        if (!headers.empty()) headerFields = joinHeaderFields(headers);
    }

    auto configure = [&](mpv_handle *m, const char *gpuCtx, const char *hwdec) {
        // Surface mpv's own diagnostics (drained by the event thread).
        mpv_request_log_messages(m, nuvioDebug() ? "v" : "no");
        // Embed into the host AWT Canvas's X11 window.
        if (!wid.empty()) mpv_set_option_string(m, "wid", wid.c_str());

        // Nuvio renders its own controls; keep mpv silent and non-interactive.
        mpv_set_option_string(m, "osc", "no");
        mpv_set_option_string(m, "osd-level", "0");
        mpv_set_option_string(m, "input-default-bindings", "no");
        mpv_set_option_string(m, "input-vo-keyboard", "no");
        mpv_set_option_string(m, "input-cursor", "no");
        mpv_set_option_string(m, "cursor-autohide", "no");
        mpv_set_option_string(m, "keep-open", "yes");
        mpv_set_option_string(m, "volume-max", "200");
        mpv_set_option_string(m, "idle", "yes");
        mpv_set_option_string(m, "vo", "gpu");
        // Bring the VO/OSD up immediately (before the first decoded frame) so the
        // controls overlay — including the loading screen — can render via
        // overlay-add while a slow/non-faststart file is still opening, instead of
        // a black gap. (This also makes mpv_initialize itself surface GPU-context
        // failures, which the attempt loop below relies on.)
        mpv_set_option_string(m, "force-window", "immediate");
        // Force an X11 GPU context so mpv embeds into the host window's X11
        // "wid". Under a Wayland session mpv would otherwise pick its native
        // Wayland backend, which cannot embed into a foreign surface and opens
        // a separate window instead. The host AWT window is X11 (XWayland), so
        // X11 embedding composites correctly inside the Nuvio window.
        mpv_set_option_string(m, "gpu-context", gpuCtx);
        mpv_set_option_string(m, "force-seekable", "yes");

        // Decoder config mirrors the macOS bridge for parity (mac: hwdec=auto +
        // gpu-hwdec-interop=auto + decoderPriority handling). gpu-hwdec-interop=auto
        // lets vo=gpu use direct hardware decode instead of the slow copy-back path.
        mpv_set_option_string(m, "audio-channels", "auto");
        // NUVIO_MPV_AUDIO_DEVICE pins mpv's audio output device (names from
        // `mpv --audio-device=help`) for HDMI/passthrough testing — same role
        // as the GPU_CONTEXT/HWDEC overrides below. Unset = mpv's default
        // routing (the system default sink).
        const char *audioDevEnv = getenv("NUVIO_MPV_AUDIO_DEVICE");
        if (audioDevEnv && *audioDevEnv) {
            mpv_set_option_string(m, "audio-device", audioDevEnv);
        }
        mpv_set_option_string(m, "hwdec", hwdec);
        mpv_set_option_string(m, "gpu-hwdec-interop", "auto");
        if (decoderPriority == 0) {
            mpv_set_option_string(m, "vd-lavc-software-fallback", "no");
        } else if (decoderPriority == 2) {
            mpv_set_option_string(m, "hwdec", "no");
            mpv_set_option_string(m, "vd-lavc-software-fallback", "yes");
        } else {
            mpv_set_option_string(m, "vd-lavc-software-fallback", "yes");
        }
        mpv_set_option_string(m, "vd-lavc-threads", "0");
        mpv_set_option_string(m, "target-colorspace-hint", "yes");
        mpv_set_option_string(m, "target-colorspace-hint-mode", "source");

        if (!headerFields.empty()) {
            mpv_set_option_string(m, "http-header-fields", headerFields.c_str());
        }
        if (initialPositionMs > 0) {
            std::string start = std::to_string(initialPositionMs / 1000.0);
            mpv_set_option_string(m, "start", start.c_str());
        }
        if (!playWhenReady) {
            mpv_set_option_string(m, "pause", "yes");
        }
    };

    // Attempt 1: x11egl — the proven path on Mesa (Intel/AMD). Attempt 2: x11vk —
    // NVIDIA's proprietary EGL refuses to make a context current on the foreign
    // AWT window (mpv_initialize fails via force-window=immediate), while Vulkan
    // embeds fine there; its native hwdec interop corrupts frames on NVIDIA, so
    // the retry pairs it with copy-back NVDEC (harmless elsewhere: unavailable
    // hwdec just falls back to software). NUVIO_MPV_GPU_CONTEXT / NUVIO_MPV_HWDEC
    // env overrides pin a single attempt for testing.
    const char *gpuCtxEnv = getenv("NUVIO_MPV_GPU_CONTEXT");
    const char *hwdecEnv = getenv("NUVIO_MPV_HWDEC");
    bool ctxPinned = gpuCtxEnv && *gpuCtxEnv;
    struct Attempt { const char *ctx; const char *hwdec; };
    Attempt attempts[2] = {
        {ctxPinned ? gpuCtxEnv : "x11egl", (hwdecEnv && *hwdecEnv) ? hwdecEnv : "auto"},
        {"x11vk", (hwdecEnv && *hwdecEnv) ? hwdecEnv : "nvdec-copy"},
    };
    int nAttempts = ctxPinned ? 1 : 2;
    int initResult = MPV_ERROR_GENERIC;
    for (int a = 0; a < nAttempts; ++a) {
        if (!player->mpv) player->mpv = mpv_create();
        if (!player->mpv) {
            NUVIO_ERR("mpv_create() returned NULL on retry");
            break;
        }
        configure(player->mpv, attempts[a].ctx, attempts[a].hwdec);
        initResult = mpv_initialize(player->mpv);
        if (initResult >= 0) {
            if (a > 0) {
                NUVIO_ERR("gpu-context %s failed to initialize; using %s instead",
                          attempts[0].ctx, attempts[a].ctx);
            }
            break;
        }
        NUVIO_ERR("mpv_initialize failed (gpu-context=%s): %s", attempts[a].ctx,
                  mpv_error_string(initResult));
        // Drain any queued log messages explaining the failure.
        for (int i = 0; i < 50; ++i) {
            mpv_event *ev = mpv_wait_event(player->mpv, 0.0);
            if (!ev || ev->event_id == MPV_EVENT_NONE) break;
            if (ev->event_id == MPV_EVENT_LOG_MESSAGE) {
                auto *m = static_cast<mpv_event_log_message *>(ev->data);
                if (m) NUVIO_LOG("mpv[%s] %s: %s", m->level, m->prefix, m->text);
            }
        }
        mpv_destroy(player->mpv);
        player->mpv = nullptr;
    }
    if (!player->mpv || initResult < 0) {
        delete player;
        return 0;
    }
    NUVIO_LOG("mpv initialized OK");

    if (eventSink != nullptr) {
        player->eventSink = env->NewGlobalRef(eventSink);
        jclass sinkClass = env->GetObjectClass(eventSink);
        player->eventMethod = env->GetMethodID(sinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");
        env->DeleteLocalRef(sinkClass);
    }

    // Register as live before starting the event thread / overlay timers so their
    // callbacks can validate the pointer (see playerAlive).
    {
        std::lock_guard<std::mutex> lk(gLiveMutex);
        gLivePlayers.insert(player);
    }

    player->running.store(true);
    player->eventThread = std::thread(runEventLoop, player);

    std::string url = jstringToUtf8(env, sourceUrl);
    const char *cmd[] = {"loadfile", url.c_str(), nullptr};
    mpv_command(player->mpv, cmd);

    // Bring up the WebKitGTK controls overlay now so it can render the loading
    // screen (poster + title + spinner) over mpv's black frame while the stream
    // buffers, matching macOS / Windows.
    if (hostViewPtr != 0) {
        ensureGtk();
        auto *setup = new WebviewSetup{player, static_cast<Window>(hostViewPtr),
                                       jstringToUtf8(env, controlsPageUrl)};
        g_main_context_invoke(nullptr, createWebviewOnGtk, setup);
    }

    return reinterpret_cast<jlong>(player);
}

JNIEXPORT void JNICALL NP(dispose)(JNIEnv *env, jobject, jlong handle) {
    Player *player = asPlayer(handle);
    if (!player) return;
    // Mark not-live first so any in-flight GTK timer bails before we free it.
    // The erase doubles as an idempotency gate: a second dispose of the same
    // handle must not re-run teardown on freed memory.
    {
        std::lock_guard<std::mutex> lk(gLiveMutex);
        if (gLivePlayers.erase(player) == 0) return;
    }
    // Tear the overlay down on the GTK thread before freeing the player.
    // Unconditionally: gtkWindow is written by the GTK thread, so reading it
    // here races a still-queued createWebviewOnGtk (fast open->close). The
    // invoke queue is FIFO — a pending create bails on !playerAlive first, and
    // destroyWebviewOnGtk checks every field it touches.
    gtkSync([player] { destroyWebviewOnGtk(player); });
    player->running.store(false);
    if (player->mpv) mpv_wakeup(player->mpv);
    if (player->eventThread.joinable()) player->eventThread.join();
    if (player->eventSink) env->DeleteGlobalRef(player->eventSink);
    if (player->mpv) mpv_terminate_destroy(player->mpv);
    delete player;
}

JNIEXPORT void JNICALL NP(setPaused)(JNIEnv *, jobject, jlong handle, jboolean paused) {
    Player *p = asPlayer(handle);
    if (p) mpvSetFlag(p->mpv, "pause", paused == JNI_TRUE);
}

JNIEXPORT void JNICALL NP(seekTo)(JNIEnv *, jobject, jlong handle, jlong positionMs) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string target = std::to_string(positionMs / 1000.0);
    const char *cmd[] = {"seek", target.c_str(), "absolute", nullptr};
    mpv_command(p->mpv, cmd);
    p->ended.store(false);
}

JNIEXPORT void JNICALL NP(seekBy)(JNIEnv *, jobject, jlong handle, jlong offsetMs) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string delta = std::to_string(offsetMs / 1000.0);
    const char *cmd[] = {"seek", delta.c_str(), "relative", nullptr};
    mpv_command(p->mpv, cmd);
    p->ended.store(false);
}

JNIEXPORT void JNICALL NP(setSpeed)(JNIEnv *, jobject, jlong handle, jfloat speed) {
    Player *p = asPlayer(handle);
    if (p) mpvSetDouble(p->mpv, "speed", speed);
}

JNIEXPORT void JNICALL NP(setVolume)(JNIEnv *, jobject, jlong handle, jfloat level) {
    Player *p = asPlayer(handle);
    if (!p) return;
    double next = level * 100.0;
    if (next < 0) next = 0;
    if (next > kMaxVolumePercent) next = kMaxVolumePercent;
    mpvSetDouble(p->mpv, "volume", next);
}

JNIEXPORT void JNICALL NP(adjustVolume)(JNIEnv *, jobject, jlong handle, jfloat delta) {
    Player *p = asPlayer(handle);
    if (!p) return;
    double current = mpvGetDouble(p->mpv, "volume");
    double next = current + delta * 100.0;
    if (next < 0) next = 0;
    if (next > kMaxVolumePercent) next = kMaxVolumePercent;
    mpvSetDouble(p->mpv, "volume", next);
}

JNIEXPORT jfloat JNICALL NP(volume)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0.0f;
    return static_cast<jfloat>(mpvGetDouble(p->mpv, "volume") / 100.0);
}

JNIEXPORT jlong JNICALL NP(durationMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    return static_cast<jlong>(mpvGetDouble(p->mpv, "duration") * 1000.0);
}

JNIEXPORT jlong JNICALL NP(positionMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    return static_cast<jlong>(mpvGetDouble(p->mpv, "time-pos") * 1000.0);
}

JNIEXPORT jlong JNICALL NP(bufferedPositionMs)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 0;
    double pos = mpvGetDouble(p->mpv, "time-pos");
    double cache = mpvGetDouble(p->mpv, "demuxer-cache-time");
    double buffered = cache > pos ? cache : pos;
    return static_cast<jlong>(buffered * 1000.0);
}

JNIEXPORT jboolean JNICALL NP(isLoading)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_TRUE;
    return playerLoading(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NP(isEnded)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_FALSE;
    // keep-open=yes makes mpv PAUSE at EOF instead of unloading, so
    // MPV_EVENT_END_FILE never fires — the `eof-reached` property is what flips.
    // Mirror the macOS bridge (rawIsEnded reads eof-reached) so Nuvio's
    // next-episode / autoplay logic actually triggers at the end of a file.
    return (mpvGetFlag(p->mpv, "eof-reached") || p->ended.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL NP(isPaused)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return JNI_FALSE;
    return mpvGetFlag(p->mpv, "pause") ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL NP(speed)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return 1.0f;
    return static_cast<jfloat>(mpvGetDouble(p->mpv, "speed"));
}

JNIEXPORT void JNICALL NP(setResizeMode)(JNIEnv *, jobject, jlong handle, jint mode) {
    Player *p = asPlayer(handle);
    if (!p) return;
    // 0 fit, 1 fill/zoom, 2 fixed-width, 3 stretch (best-effort mpv mapping)
    switch (mode) {
        case 3: mpv_set_option_string(p->mpv, "keepaspect", "no"); break;
        case 1: mpv_set_option_string(p->mpv, "keepaspect", "yes");
                mpv_set_option_string(p->mpv, "panscan", "1.0"); break;
        default: mpv_set_option_string(p->mpv, "keepaspect", "yes");
                 mpv_set_option_string(p->mpv, "panscan", "0.0"); break;
    }
}

// ---- tracks & subtitles (mpv properties) --------------------------------

JNIEXPORT jstring JNICALL NP(audioTracksJson)(JNIEnv *env, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return utf8ToJstring(env, "[]");
    return utf8ToJstring(env, buildTracksJson(p->mpv, "audio"));
}

JNIEXPORT jstring JNICALL NP(subtitleTracksJson)(JNIEnv *env, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return utf8ToJstring(env, "[]");
    return utf8ToJstring(env, buildTracksJson(p->mpv, "sub"));
}

JNIEXPORT void JNICALL NP(selectAudioTrack)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "aid", "no");
    else mpv_set_property(p->mpv, "aid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(selectSubtitleTrack)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "sid", "no");
    else mpv_set_property(p->mpv, "sid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(addSubtitleUrl)(JNIEnv *env, jobject, jlong handle, jstring url) {
    Player *p = asPlayer(handle);
    if (!p) return;
    std::string sub = jstringToUtf8(env, url);
    const char *cmd[] = {"sub-add", sub.c_str(), "select", nullptr};
    mpv_command(p->mpv, cmd);
}

JNIEXPORT void JNICALL NP(clearExternalSubtitles)(JNIEnv *, jobject, jlong handle) {
    Player *p = asPlayer(handle);
    if (!p) return;
    const char *cmd[] = {"sub-remove", nullptr};
    mpv_command(p->mpv, cmd);
}

JNIEXPORT void JNICALL NP(clearExternalSubtitlesAndSelect)(JNIEnv *, jobject, jlong handle, jint trackId) {
    Player *p = asPlayer(handle);
    if (!p) return;
    const char *cmd[] = {"sub-remove", nullptr};
    mpv_command(p->mpv, cmd);
    int64_t id = trackId;
    if (trackId < 0) mpv_set_property_string(p->mpv, "sid", "no");
    else mpv_set_property(p->mpv, "sid", MPV_FORMAT_INT64, &id);
}

JNIEXPORT void JNICALL NP(setSubtitleDelayMs)(JNIEnv *, jobject, jlong handle, jint delayMs) {
    Player *p = asPlayer(handle);
    if (p) mpvSetDouble(p->mpv, "sub-delay", delayMs / 1000.0);
}

JNIEXPORT void JNICALL NP(applySubtitleStyle)(
    JNIEnv *env, jobject, jlong handle, jstring textColor, jstring /*backgroundColor*/,
    jstring outlineColor, jfloat outlineSize, jboolean bold, jfloat fontSize, jint subPos,
    jboolean useLibass, jboolean stripSdh) {
    Player *p = asPlayer(handle);
    if (!p) return;
    // Keep the track's own ASS styling when libass rendering is on, and let the
    // settings below take over when it is off (matching the Windows bridge).
    mpv_set_property_string(p->mpv, "sub-ass-override",
                            useLibass == JNI_TRUE ? "scale" : "force");
    mpv_set_property_string(p->mpv, "sub-color", jstringToUtf8(env, textColor).c_str());
    mpv_set_property_string(p->mpv, "sub-border-color", jstringToUtf8(env, outlineColor).c_str());
    std::string border = std::to_string(outlineSize);
    mpv_set_property_string(p->mpv, "sub-border-size", border.c_str());
    mpv_set_property_string(p->mpv, "sub-bold", bold == JNI_TRUE ? "yes" : "no");
    std::string size = std::to_string(fontSize);
    mpv_set_property_string(p->mpv, "sub-font-size", size.c_str());
    std::string pos = std::to_string(subPos);
    mpv_set_property_string(p->mpv, "sub-pos", pos.c_str());
    // Strip captions written for deaf and hard-of-hearing viewers.
    mpv_set_property_string(p->mpv, "sub-filter-sdh", stripSdh == JNI_TRUE ? "yes" : "no");
    mpv_set_property_string(p->mpv, "sub-filter-sdh-harder", stripSdh == JNI_TRUE ? "yes" : "no");
}

// ---- Phase 2 stubs: webview controls / window chrome / focus ------------

JNIEXPORT void JNICALL NP(updateControls)(JNIEnv *env, jobject, jlong handle, jstring controlsJson) {
    Player *p = asPlayer(handle);
    // No webview check: the webview is created asynchronously after create()
    // returns, and the first (often only) payload with the loading-screen
    // metadata arrives in exactly that window. Buffer it; the flush delivers it
    // once the page is up.
    if (!p) return;
    auto *u = new ControlsUpdate{p, jstringToUtf8(env, controlsJson)};
    g_main_context_invoke(nullptr, applyControlsOnGtk, u);
}
// Re-grant the overlay X input focus (initial grab happens at webview
// creation; Kotlin calls this on fullscreen changes and window refocus).
JNIEXPORT void JNICALL NP(requestFocus)(JNIEnv *, jobject, jlong handle) {
    auto *player = reinterpret_cast<Player *>(handle);
    if (!player) return;
    g_main_context_invoke(nullptr,
                          +[](gpointer data) -> gboolean {
                              auto *p = static_cast<Player *>(data);
                              if (playerAlive(p)) focusOverlay(p);
                              return G_SOURCE_REMOVE;
                          },
                          player);
}
JNIEXPORT void JNICALL NP(applyWindowChrome)(JNIEnv *, jobject, jlong, jboolean, jint, jint, jint) {}
JNIEXPORT void JNICALL NP(setWindowBorderlessFullscreen)(
    JNIEnv *, jobject, jlong, jboolean, jint, jint, jint, jint) {}
// Named for its WebView2 origin; on Linux it warms the WebKitGTK engine (the
// JNI name is shared across the desktop bridges). Blocks up to 5s like the
// Windows implementation — Kotlin calls it from a daemon preload thread.
JNIEXPORT jboolean JNICALL NP(warmupWebView2)(JNIEnv *env, jobject, jstring controlsPageUrl) {
    {
        std::lock_guard<std::mutex> lock(gWarmupMutex);
        if (gWarmupStarted) return (gWarmupDone && gWarmupSucceeded) ? JNI_TRUE : JNI_FALSE;
        gWarmupStarted = true;
    }
    ensureGtk();
    g_main_context_invoke(nullptr, warmupOnGtk,
                          new std::string(jstringToUtf8(env, controlsPageUrl)));
    std::unique_lock<std::mutex> lock(gWarmupMutex);
    bool completed = gWarmupCv.wait_for(lock, std::chrono::seconds(5),
                                        [] { return gWarmupDone; });
    NUVIO_LOG("webview warmup %s",
              !completed ? "timed out" : gWarmupSucceeded ? "ready" : "failed");
    return (completed && gWarmupSucceeded) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL NP(shutdownWebView2Warmup)(JNIEnv *, jobject) {
    if (gShuttingDown.load()) return;
    g_main_context_invoke(nullptr,
                          +[](gpointer) -> gboolean {
                              if (gWarmupWindow) {
                                  // Same fatal-X-error exposure as the overlay
                                  // teardown (see destroyWebviewOnGtk): the
                                  // webview's dispose queries the pointer
                                  // position while this window is dying.
                                  GdkDisplay *d =
                                      gtk_widget_get_display(gWarmupWindow);
                                  gdk_x11_display_error_trap_push(d);
                                  gtk_widget_destroy(gWarmupWindow);
                                  gdk_x11_display_error_trap_pop_ignored(d);
                                  // The view/ucm die with their window — null
                                  // them too or the adoption gate could later
                                  // read dangling pointers.
                                  gWarmupWindow = nullptr;
                                  gWarmupView = nullptr;
                                  gWarmupUcm = nullptr;
                              }
                              return G_SOURCE_REMOVE;
                          },
                          nullptr);
}
JNIEXPORT jboolean JNICALL NP(setWindowsDisplaySleepInhibited)(JNIEnv *, jobject, jboolean) {
    return JNI_FALSE;
}

#undef NP
} // extern "C"
