// NuvioImageBridge.dll
//
// Decodes still images (JPEG, PNG, BMP, TIFF, ...) on Windows through the
// Windows Imaging Component (WIC, `windowscodecs.dll`). WIC is the same
// pipeline Microsoft Edge, the Photos app, and File Explorer use to render
// images on Windows; its `WICBitmapInterpolationModeHighQualityCubic`
// produces visually clean downscales at any ratio thanks to a kernel that
// adapts to the scale factor (see Microsoft docs / production usage in
// `microsoft/PowerToys`, `microsoft/DirectML`, `winsiderss/systeminformer`,
// `GStreamer/gstreamer`).
//
// The companion JVM-side Coil 3 Decoder calls `nuvio_image_decode_scaled`
// with the encoded bytes plus a target draw size; we do the decode and the
// downscale here, hand back tightly-packed pre-multiplied BGRA pixels in a
// caller-allocated buffer, and Skia / Compose just blits the bitmap. No
// Mitchell sampler, no GL mip-pyramid race, no FilterQuality knobs.
//
// Pure WIC C API, no WIL/WRL/winrt::ComPtr, so we can compile against the
// MSVC build tools that the rest of the project uses.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include <windows.h>
#include <wincodec.h>
#include <combaseapi.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

namespace {

// COM lifecycle: WIC requires a thread-aware COM apartment. We initialize
// the calling thread on first use as multi-threaded apartment (MTA).
// Coil's decoder runs on a Coroutines IO dispatcher pool, so callers may
// arrive on different threads; MTA lets every worker thread share the
// same WIC factory without re-initialising COM around every call.
thread_local bool g_thread_com_initialized = false;

void EnsureComInitialized() {
    if (g_thread_com_initialized) return;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    // RPC_E_CHANGED_MODE means the thread already had a different apartment
    // mode (e.g. an AWT/Skiko thread that asked for STA). That's fine; WIC
    // works in either, we just don't try to change it.
    if (SUCCEEDED(hr) || hr == RPC_E_CHANGED_MODE || hr == S_FALSE) {
        g_thread_com_initialized = true;
    }
}

// Single process-wide WIC factory. IWICImagingFactory is documented as
// thread-safe.
std::once_flag g_factory_once;
IWICImagingFactory* g_factory = nullptr;

IWICImagingFactory* GetFactory() {
    std::call_once(g_factory_once, [] {
        EnsureComInitialized();
        IWICImagingFactory* factory = nullptr;
        HRESULT hr = CoCreateInstance(
            CLSID_WICImagingFactory,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(&factory));
        if (SUCCEEDED(hr)) {
            g_factory = factory;
        }
    });
    EnsureComInitialized();
    return g_factory;
}

template <typename T>
void SafeRelease(T*& ptr) {
    if (ptr) {
        ptr->Release();
        ptr = nullptr;
    }
}

} // namespace

// Result codes returned by nuvio_image_decode_scaled. Kept as plain int
// to make the JNA mapping trivial.
enum NuvioImageResult : int32_t {
    NUVIO_IMG_OK = 0,
    NUVIO_IMG_ERR_INVALID_ARG = -1,
    NUVIO_IMG_ERR_FACTORY = -2,
    NUVIO_IMG_ERR_DECODE = -3,
    NUVIO_IMG_ERR_FRAME = -4,
    NUVIO_IMG_ERR_SCALE = -5,
    NUVIO_IMG_ERR_CONVERT = -6,
    NUVIO_IMG_ERR_COPY = -7,
    NUVIO_IMG_ERR_BUFFER_TOO_SMALL = -8,
};

extern "C" {

// Probe the encoded bytes to read the source image dimensions without
// fully decoding. Used by the Kotlin side to compute a target downscale
// size before allocating the destination buffer.
//
// Returns NUVIO_IMG_OK on success and writes the source pixel dimensions
// to *out_width / *out_height. Any failure leaves *out_width / *out_height
// untouched and returns a negative error code.
__declspec(dllexport) int32_t nuvio_image_probe_size(
    const uint8_t* input,
    int32_t input_size,
    int32_t* out_width,
    int32_t* out_height) {

    if (!input || input_size <= 0 || !out_width || !out_height) {
        return NUVIO_IMG_ERR_INVALID_ARG;
    }

    IWICImagingFactory* factory = GetFactory();
    if (!factory) return NUVIO_IMG_ERR_FACTORY;

    IWICStream* stream = nullptr;
    IWICBitmapDecoder* decoder = nullptr;
    IWICBitmapFrameDecode* frame = nullptr;
    int32_t result = NUVIO_IMG_OK;

    do {
        if (FAILED(factory->CreateStream(&stream))) { result = NUVIO_IMG_ERR_DECODE; break; }
        if (FAILED(stream->InitializeFromMemory(const_cast<BYTE*>(input), static_cast<DWORD>(input_size)))) {
            result = NUVIO_IMG_ERR_DECODE; break;
        }
        if (FAILED(factory->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnDemand, &decoder))) {
            result = NUVIO_IMG_ERR_DECODE; break;
        }
        if (FAILED(decoder->GetFrame(0, &frame))) { result = NUVIO_IMG_ERR_FRAME; break; }

        UINT w = 0, h = 0;
        if (FAILED(frame->GetSize(&w, &h)) || w == 0 || h == 0) {
            result = NUVIO_IMG_ERR_FRAME; break;
        }
        *out_width = static_cast<int32_t>(w);
        *out_height = static_cast<int32_t>(h);
    } while (false);

    SafeRelease(frame);
    SafeRelease(decoder);
    SafeRelease(stream);
    return result;
}

// Decode + downscale + colorspace-convert in one shot.
//
// - input/input_size: encoded source bytes (any WIC-recognized format).
// - target_width/target_height: desired output dimensions in pixels.
//   Pass the source dimensions to skip the resampling step. Either may be
//   <= 0 to mean "no scaling".
// - out_buffer: caller-allocated buffer that must be at least
//   (target_width * 4 * target_height) bytes.
// - out_buffer_size: capacity of out_buffer in bytes; checked.
// - out_row_stride_bytes: actual row stride WIC writes; equals
//   target_width * 4 in the normal case but the caller should honor it.
//
// Pixels are written as PBGRA (B, G, R, A pre-multiplied) in row-major
// top-down order. This is the layout `org.jetbrains.skia.Bitmap` expects
// for `ColorType.BGRA_8888` + `ColorAlphaType.PREMUL`, so the JVM side
// can wrap the buffer directly without another conversion pass.
__declspec(dllexport) int32_t nuvio_image_decode_scaled(
    const uint8_t* input,
    int32_t input_size,
    int32_t target_width,
    int32_t target_height,
    uint8_t* out_buffer,
    int32_t out_buffer_size,
    int32_t* out_row_stride_bytes) {

    if (!input || input_size <= 0 || !out_buffer || out_buffer_size <= 0) {
        return NUVIO_IMG_ERR_INVALID_ARG;
    }

    IWICImagingFactory* factory = GetFactory();
    if (!factory) return NUVIO_IMG_ERR_FACTORY;

    IWICStream* stream = nullptr;
    IWICBitmapDecoder* decoder = nullptr;
    IWICBitmapFrameDecode* frame = nullptr;
    IWICBitmapSource* source = nullptr;            // the current "tip" of the conversion chain
    IWICBitmapScaler* scaler = nullptr;
    IWICFormatConverter* converter = nullptr;
    int32_t result = NUVIO_IMG_OK;

    do {
        if (FAILED(factory->CreateStream(&stream))) { result = NUVIO_IMG_ERR_DECODE; break; }
        if (FAILED(stream->InitializeFromMemory(const_cast<BYTE*>(input), static_cast<DWORD>(input_size)))) {
            result = NUVIO_IMG_ERR_DECODE; break;
        }
        if (FAILED(factory->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnDemand, &decoder))) {
            result = NUVIO_IMG_ERR_DECODE; break;
        }
        if (FAILED(decoder->GetFrame(0, &frame))) { result = NUVIO_IMG_ERR_FRAME; break; }

        UINT src_w = 0, src_h = 0;
        if (FAILED(frame->GetSize(&src_w, &src_h)) || src_w == 0 || src_h == 0) {
            result = NUVIO_IMG_ERR_FRAME; break;
        }

        UINT dst_w = (target_width > 0)
            ? static_cast<UINT>(target_width)
            : src_w;
        UINT dst_h = (target_height > 0)
            ? static_cast<UINT>(target_height)
            : src_h;

        // The scaler step is skipped when the caller asked for native size
        // or zero/negative target. Cuts a memcopy and a kernel pass for
        // small icons / already-sized sources.
        if (dst_w == src_w && dst_h == src_h) {
            // hand the frame straight to the converter
            source = frame;
            source->AddRef();
        } else {
            if (FAILED(factory->CreateBitmapScaler(&scaler))) { result = NUVIO_IMG_ERR_SCALE; break; }
            // High-quality cubic = denser kernel that adapts to the scale
            // factor; documented as "suitable for downscaling by factors
            // greater than 2" by Microsoft. Same mode used by PowerToys,
            // SystemInformer, GStreamer's WIC JPEG decoder.
            if (FAILED(scaler->Initialize(frame, dst_w, dst_h, WICBitmapInterpolationModeHighQualityCubic))) {
                result = NUVIO_IMG_ERR_SCALE; break;
            }
            source = scaler;
            source->AddRef();
        }

        // Convert to 32bpp pre-multiplied BGRA, the layout Skia expects
        // for BGRA_8888 / PREMUL.
        if (FAILED(factory->CreateFormatConverter(&converter))) {
            result = NUVIO_IMG_ERR_CONVERT; break;
        }
        if (FAILED(converter->Initialize(
                source,
                GUID_WICPixelFormat32bppPBGRA,
                WICBitmapDitherTypeNone,
                nullptr,
                0.0,
                WICBitmapPaletteTypeMedianCut))) {
            result = NUVIO_IMG_ERR_CONVERT; break;
        }

        // Compute the row stride and check the caller's buffer can hold the
        // full image. Stride = width * bytesPerPixel; tightly packed.
        const UINT bytes_per_pixel = 4;
        const UINT row_stride = dst_w * bytes_per_pixel;
        const UINT total_bytes = row_stride * dst_h;
        if (total_bytes > static_cast<UINT>(out_buffer_size)) {
            result = NUVIO_IMG_ERR_BUFFER_TOO_SMALL; break;
        }

        // CopyPixels() writes into the caller's buffer.
        if (FAILED(converter->CopyPixels(nullptr, row_stride, total_bytes, out_buffer))) {
            result = NUVIO_IMG_ERR_COPY; break;
        }

        if (out_row_stride_bytes) {
            *out_row_stride_bytes = static_cast<int32_t>(row_stride);
        }
    } while (false);

    SafeRelease(converter);
    SafeRelease(scaler);
    SafeRelease(source);
    SafeRelease(frame);
    SafeRelease(decoder);
    SafeRelease(stream);
    return result;
}

} // extern "C"
