# NuvioDesktop - Wayland EGL GPU Rendering Implementation

## Overview

Added Wayland EGL GPU rendering support to NuvioDesktop's Linux video player.
mpv now renders via OpenGL (GPU-accelerated) instead of software rendering on Wayland sessions.

## Files Modified

### 1. DesktopHostOs.kt
**Path:** `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopHostOs.kt`

Added `isWayland` detection:
```kotlin
val isWayland: Boolean by lazy {
    if (current != LINUX) false
    else {
        val sessionType = System.getenv("XDG_SESSION_TYPE").orEmpty()
        val waylandDisplay = System.getenv("WAYLAND_DISPLAY").orEmpty()
        sessionType.contains("wayland") || waylandDisplay.isNotEmpty()
    }
}
```

### 2. player_bridge.c
**Path:** `composeApp/src/desktopMain/native/linux/player_bridge.c`

Complete rewrite (was minified, now properly formatted).

**New includes:**
```c
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <wayland-client.h>
#include <wayland-egl.h>
```

**New fields in CreateTask struct:**
```c
/* Wayland EGL state (gpuMode=2) */
EGLDisplay eglDisplay;
EGLSurface eglSurface;
EGLContext eglContext;
struct wl_egl_window *eglWindow;
struct wl_display *wlDisplay;
struct wl_surface *wlSurface;
GLuint fbo;
GLuint glTexture;
int eglTexW;
int eglTexH;
```

**New helper functions:**
```c
static int is_wayland_session(void);
static void *egl_get_proc_address(void *ctx, const char *name);
```

**Rendering modes:**
- `gpuMode=0`: SW rendering (MPV_RENDER_API_TYPE_SW) - CPU readback
- `gpuMode=1`: X11 GPU (vo=gpu-next + wid) - mpv renders directly
- `gpuMode=2`: Wayland EGL (MPV_RENDER_API_TYPE_OPENGL) - NEW

**Wayland EGL initialization flow:**
1. Detect Wayland via XDG_SESSION_TYPE / WAYLAND_DISPLAY
2. Connect to Wayland display: wl_display_connect(NULL)
3. Get EGL display: eglGetPlatformDisplayEXT(EGL_PLATFORM_WAYLAND_EXT, ...)
4. Create PBuffer surface (offscreen, no wl_surface needed)
5. Create EGL context (OpenGL ES 2.0)
6. Create FBO + GL texture for mpv rendering
7. Create mpv render context with MPV_RENDER_API_TYPE_OPENGL
8. Start render thread

**Render thread (gpuMode=2):**
```c
mpv_render_context_update(task->renderCtx);
mpv_opengl_fbo mpv_fbo = { .fbo = task->fbo, .w = ..., .h = ... };
mpv_render_context_render(task->renderCtx, params);  // renders to FBO
mpv_render_context_report_swap(task->renderCtx);
task->frameReady = 1;
```

**renderFrame() for gpuMode=2:**
```c
glBindFramebuffer(GL_FRAMEBUFFER, task->fbo);
glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba_buf);
// Convert RGBA (GL) to ARGB (Java), flip Y
```

**Fallback:** If EGL initialization fails, automatically falls back to SW mode.

### 3. NativePlayerBridge.kt
**Path:** `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt`

Added helper:
```kotlin
fun isWayland(): Boolean = DesktopHostOs.isWayland
```

### 4. build.gradle.kts
**Path:** `composeApp/build.gradle.kts`

Added linker flags to buildLinuxPlayerBridge task:
```kotlin
commandLine(
    "gcc", "-shared", "-fPIC",
    // ... existing flags ...
    "-lm",
    "-lEGL", "-lGLESv2", "-lwayland-client", "-lwayland-egl",  // NEW
)
```

## Build Instructions (Linux)

```bash
# Install required packages
sudo apt install \
  libegl-dev \
  libgles2-mesa-dev \
  libgl-dev \
  libwayland-dev \
  libwayland-egl1 \
  libmpv-dev

# Build native bridge
./gradlew :composeApp:buildLinuxPlayerBridge

# Run
./gradlew :composeApp:run
```

## How It Works

```
┌─────────────────────────────────────────────────────┐
│  NuvioDesktop (Compose Desktop)                     │
├─────────────────────────────────────────────────────┤
│  Compose Canvas (Skia)                              │
│  ┌───────────────────────────────────────────┐      │
│  │  glReadPixels(rgba_buf)                   │      │
│  │  ↓ ARGB conversion                       │      │
│  │  Image.makeRaster(pixels)                │      │
│  │  ↓ Skia draws on Canvas                  │      │
│  └───────────────────────────────────────────┘      │
├─────────────────────────────────────────────────────┤
│  player_bridge.c (gpuMode=2)                        │
│  ┌───────────────────────────────────────────┐      │
│  │  mpv_render_context_render(FBO)           │      │
│  │  ↓ GL texture                             │      │
│  │  eglSwapBuffers (PBuffer)                 │      │
│  │  ↓ EGL context                            │      │
│  │  mpv (vo=libmpv, hwdec=auto)             │      │
│  └───────────────────────────────────────────┘      │
├─────────────────────────────────────────────────────┤
│  System Libraries                                   │
│  libmpv.so.2 + libEGL.so.1 + libGLESv2.so.2        │
│  + libwayland-client.so.0 + libwayland-egl.so.1     │
└─────────────────────────────────────────────────────┘
```

## Performance Comparison

| Metric | SW Mode (gpuMode=0) | EGL Mode (gpuMode=2) |
|--------|---------------------|----------------------|
| Video Decode | CPU (hwdec=auto-copy) | GPU (hwdec=auto) |
| Rendering | CPU software rasterizer | GPU OpenGL |
| Color Processing | CPU | GPU shaders |
| Frame Transfer | mpv_render_context_render(SW) | glReadPixels |
| Expected Speedup | baseline | 3-5x faster |

## Testing Checklist

- [ ] Build compiles without errors on Linux
- [ ] App starts on Wayland session
- [ ] Wayland detection works (check logs for "gpuMode=2")
- [ ] EGL context created successfully
- [ ] FBO + texture created
- [ ] mpv render context created with OpenGL
- [ ] Video plays with GPU acceleration
- [ ] Fallback to SW mode works when EGL fails
- [ ] No crashes on dispose/cleanup
- [ ] Performance is acceptable for 1080p/4K video

## Known Limitations

1. **PBuffer + glReadPixels**: Still requires GPU->CPU readback, but with GPU-accelerated decode and color processing. True zero-copy would require direct Wayland surface rendering (not possible with Compose Desktop's rendering model).

2. **GPU Driver Compatibility**: EGL on Wayland may not work with all GPU drivers. Fallback to SW mode handles this gracefully.

3. **Texture Size**: Currently hardcoded to 1920x1080 PBuffer. Could be made dynamic based on video resolution.

## Future Improvements

1. Dynamic PBuffer sizing based on video dimensions  
2. **Shared GL texture between mpv and Skia** (see below)
3. Direct Wayland subsurface rendering (requires Compose Desktop rendering changes)
4. Hardware-accelerated color space conversion

## How to Share GL Texture Between mpv and Skia (Eliminate Readback)

The current pipeline's main bottleneck for 4K is `glReadPixels` at line 321 of `player_bridge.c`:
```
GPU → [glReadPixels 33MB] → CPU → [JNI copy] → ByteArray → [Image.makeRaster] → Skia → GPU
```

### Practical Approaches

#### Approach A: EGL Context Sharing (Recommended - Medium Effort)

Create mpv's EGL context as a **shared context** of Skia's (Skiko's) EGL context so both can access the same GL texture.

**How to get Skia's EGL context:**

On Linux, Skiko (Skia for Kotlin) creates its EGL context in `LinuxOpenGLContext` (from `skiko` library). Before initializing mpv, capture the current EGL context during the first frame render:

```kotlin
// In WaylandPlayerHost.kt - capture Skiko's GL context
init {
    // During the first Canvas draw, Skiko will have made its
    // EGL context current. We capture it at native init time
    // by calling a JNI function that does eglGetCurrentContext()
    val skiaDisplay = captureSkiaEglDisplay()
    val skiaContext = captureSkiaEglContext()
    NativePlayerBridge.initEglShared(skiaDisplay, skiaContext)
}
```

```c
// In player_bridge.c - new function
JNIEXPORT void JNICALL Java_..._initEglShared(
    JNIEnv *env, jobject thiz, jlong skiaDisplay, jlong skiaContext)
{
    // Create mpv's EGL context as shared with Skia's
    EGLDisplay dpy = (EGLDisplay)(intptr_t)skiaDisplay;
    EGLContext sharedCtx = (EGLContext)(intptr_t)skiaContext;
    
    EGLContext mpvCtx = eglCreateContext(dpy, eglConfig, sharedCtx, ctxAttribs);
    // mpv can now access textures from Skia's context and vice versa
}
```

Then mpv renders to an FBO with a texture. The **texture ID** is passed back to Skia:

```kotlin
// In WaylandPlayerHost.kt - render using shared texture
fun renderFrame(): Boolean {
    val texId = NativePlayerBridge.renderFrameToTexture(handle)
    if (texId == 0) return false
    // Create Skia Image from OpenGL texture ID
    // Uses Skia's GrBackendTexture API
    val backendTex = GrBackendTexture.makeGL(width, height, 
        GrGLTextureInfo(texId, GL_RGBA8))
    val skiaImage = Image.makeFromBackendTexture(
        drawContext.canvas.nativeCanvas.recordingContext,
        backendTex,
        origin = SurfaceOrigin.TOP_LEFT,
        colorType = ColorType.RGBA_8888,
        alphaType = AlphaType.PREMUL
    )
    // Draw without any CPU readback
}
```

This eliminates all 4 copies (glReadPixels + JNI + Image.makeRaster + Skia upload).

#### Approach B: Optimize Readback with PBO + DirectBuffer (Lower Effort)

If GL context sharing is not feasible, optimize the existing pipeline:

1. **PBO (Pixel Buffer Object)** for non-blocking async readback
2. **Direct ByteBuffer** to eliminate JNI array copy

```c
// Add PBO tripe buffering to player_bridge.c
// In CreateTask struct:
GLuint pboIds[3];
int pboReadIndex;   // Index being read by JNI
int pboWriteIndex;  // Index being written by render thread

// In render thread (async readback):
glBindBuffer(GL_PIXEL_PACK_BUFFER, task->pboIds[task->pboWriteIndex]);
glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, NULL); // Non-blocking
task->pboWriteIndex = (task->pboWriteIndex + 1) % 3;

// In JNI renderFrameBytes (map completed PBO):
glBindBuffer(GL_PIXEL_PACK_BUFFER, task->pboIds[task->pboReadIndex]);
void *pixels = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY);
// Copy directly to JNI-provided DirectBuffer (no JNI array pinning)
memcpy(directBuffer, pixels, ...);
glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
task->pboReadIndex = (task->pboReadIndex + 1) % 3;
```

```kotlin
// Use DirectBuffer instead of ByteArray
val directBuf = ByteBuffer.allocateDirect(width * height * 4)
NativePlayerBridge.renderFrameDirect(handle, directBuf, w, h)
// directBuf now has the frame data with zero JNI copy
```

#### Approach C: Wayland Subsurface (Most Complex, Best Performance)

Create a separate `wl_surface` for mpv video rendering as a subsurface of Compose's window surface:

1. Get Compose window's `wl_surface` (via `libdecor` or `xdg-shell`)
2. Create a `wl_subsurface` for mpv
3. mpv renders directly (zero-copy via `vo=gpu-next` + Wayland)
4. Compose draws UI over it with transparent video area

This is what Soia does internally and provides true zero-copy 4K performance.

### Summary

| Approach | Effort | 4K Perf Gain | Complexity |
|----------|--------|-------------|------------|
| **A: EGL Context Sharing** | Medium | 10-20x | Medium |
| **B: PBO + DirectBuffer** | Low | 2-3x | Low |
| **C: Wayland Subsurface** | High | 20-50x (zero-copy) | High |

**Recommendation:** Start with Approach B (low effort, immediate gain), then move to Approach A (true texture sharing), and eventually consider Approach C if Compose performance still limits 4K playback.
