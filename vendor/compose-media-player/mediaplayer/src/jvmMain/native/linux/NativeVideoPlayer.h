// NativeVideoPlayer.h — Linux GStreamer-based native video player
// Pure C API for JNI consumption.

#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque player handle
typedef struct VideoPlayer VideoPlayer;

// Lifecycle
VideoPlayer* nvp_create(void);
void         nvp_destroy(VideoPlayer* p);

// Playback control
int  nvp_open_uri(VideoPlayer* p, const char* uri);
void nvp_play(VideoPlayer* p);
void nvp_pause(VideoPlayer* p);
void nvp_set_volume(VideoPlayer* p, float volume);
float nvp_get_volume(VideoPlayer* p);
void nvp_seek_to(VideoPlayer* p, double time_seconds);
void nvp_set_playback_speed(VideoPlayer* p, float speed);
float nvp_get_playback_speed(VideoPlayer* p);

// Frame access
enum {
    NVP_FRAME_COPY_INVALID = -1,
    NVP_FRAME_COPY_DEST_TOO_SMALL = -2,
    NVP_FRAME_COPY_SIZE_CHANGED = -3,
    NVP_FRAME_COPY_NOT_READY = 0,
    NVP_FRAME_COPY_OK = 1,
};

typedef struct NvpFrameInfo {
    int32_t width;
    int32_t height;
    int32_t source_stride;
} NvpFrameInfo;

int32_t nvp_copy_latest_frame(
    VideoPlayer* p,
    void* destination,
    size_t destination_capacity,
    int32_t expected_width,
    int32_t expected_height,
    int32_t destination_stride,
    NvpFrameInfo* out_info
);
int32_t nvp_get_frame_width(VideoPlayer* p);
int32_t nvp_get_frame_height(VideoPlayer* p);
int32_t nvp_set_output_size(VideoPlayer* p, int32_t width, int32_t height);

#ifdef NVP_TESTING
int32_t nvp_test_publish_bgra(
    VideoPlayer* p,
    const void* source,
    int32_t width,
    int32_t height,
    int32_t source_stride
);
void nvp_test_pause_next_copy(VideoPlayer* p);
void nvp_test_wait_until_copy_paused(VideoPlayer* p);
void nvp_test_resume_copy(VideoPlayer* p);
int32_t nvp_test_try_frame_lock(VideoPlayer* p);
void nvp_test_arm_publish_attempt(VideoPlayer* p);
int32_t nvp_test_wait_until_publish_attempted(VideoPlayer* p);
#endif

// Timing
double nvp_get_duration(VideoPlayer* p);
double nvp_get_current_time(VideoPlayer* p);

// Metadata (caller must free returned strings with free())
char*   nvp_get_title(VideoPlayer* p);
int64_t nvp_get_bitrate(VideoPlayer* p);
char*   nvp_get_mime_type(VideoPlayer* p);
int32_t nvp_get_audio_channels(VideoPlayer* p);
int32_t nvp_get_audio_sample_rate(VideoPlayer* p);
float   nvp_get_frame_rate(VideoPlayer* p);

// End-of-stream notification (consumes the flag)
int32_t nvp_consume_did_play_to_end(VideoPlayer* p);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
