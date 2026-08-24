// NativeVideoPlayer.c — Linux GStreamer-based native video player
// Uses GStreamer C API directly: playbin + appsink + level element.
// Bus messages are processed by a dedicated polling thread (no GLib main loop needed).

#include "NativeVideoPlayer.h"

#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/video/video-info.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

// ---------------------------------------------------------------------------
// Internal structures
// ---------------------------------------------------------------------------

struct VideoPlayer {
    GstElement* pipeline;   // playbin
    GstElement* video_sink; // appsink
    GstElement* audio_bin;  // custom audio bin with scaletempo + level
    GstElement* level;      // level element reference

    // Frame buffer (BGRA)
    pthread_mutex_t frame_lock;
    uint8_t* frame_buffer;
    int32_t  frame_width;
    int32_t  frame_height;
    size_t   frame_size;

    // Output scaling
    int32_t output_width;
    int32_t output_height;

    // Metadata
    pthread_mutex_t meta_lock;
    char*   title;
    int64_t bitrate;
    char*   mime_type;
    int32_t audio_channels;
    int32_t audio_sample_rate;
    float   frame_rate;

    // Playback state
    float   volume;
    float   playback_speed;
    int     did_play_to_end; // atomic flag

    // Bus polling thread
    pthread_t bus_thread;
    volatile int bus_thread_running;
};

// ---------------------------------------------------------------------------
// Forward declarations
// ---------------------------------------------------------------------------

static void  process_bus_message(VideoPlayer* p, GstMessage* msg);
static void* bus_thread_func(void* data);
static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer data);
static void  update_metadata_from_tags(VideoPlayer* p, GstTagList* tags);
static void  update_stream_metadata(VideoPlayer* p);

// ---------------------------------------------------------------------------
// GStreamer init (once)
// ---------------------------------------------------------------------------

static pthread_once_t gst_init_once = PTHREAD_ONCE_INIT;

static void gst_init_func(void) {
    gst_init(NULL, NULL);
}

// ---------------------------------------------------------------------------
// Bus polling thread
// ---------------------------------------------------------------------------

static void* bus_thread_func(void* data) {
    VideoPlayer* p = (VideoPlayer*)data;
    GstBus* bus = gst_element_get_bus(p->pipeline);

    while (p->bus_thread_running) {
        // Block up to 100ms waiting for a message
        GstMessage* msg = gst_bus_timed_pop(bus, 100 * GST_MSECOND);
        if (msg) {
            process_bus_message(p, msg);
            gst_message_unref(msg);
        }
    }

    gst_object_unref(bus);
    return NULL;
}

static void process_bus_message(VideoPlayer* p, GstMessage* msg) {
    switch (GST_MESSAGE_TYPE(msg)) {
    case GST_MESSAGE_EOS:
        __sync_lock_test_and_set(&p->did_play_to_end, 1);
        break;

    case GST_MESSAGE_ERROR: {
        GError* err = NULL;
        gchar* debug = NULL;
        gst_message_parse_error(msg, &err, &debug);
        if (err) {
            g_printerr("GStreamer error: %s\n", err->message);
            g_error_free(err);
        }
        if (debug) g_free(debug);
        break;
    }

    case GST_MESSAGE_TAG: {
        GstTagList* tags = NULL;
        gst_message_parse_tag(msg, &tags);
        if (tags) {
            update_metadata_from_tags(p, tags);
            gst_tag_list_unref(tags);
        }
        break;
    }

    case GST_MESSAGE_STATE_CHANGED: {
        if (GST_MESSAGE_SRC(msg) == GST_OBJECT(p->pipeline)) {
            GstState old_state, new_state;
            gst_message_parse_state_changed(msg, &old_state, &new_state, NULL);
            if (new_state == GST_STATE_PAUSED || new_state == GST_STATE_PLAYING) {
                update_stream_metadata(p);
            }
        }
        break;
    }

    default:
        break;
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

// CDNs (googlevideo among them) reject soup's default user agent with 403,
// which surfaces as typefind "not enough data". Present a browser UA on any
// source that takes one.
static void on_source_setup(GstElement* pipeline, GstElement* source, gpointer user_data) {
    (void)pipeline; (void)user_data;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "user-agent")) {
        g_object_set(source, "user-agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36", NULL);
    }
}

VideoPlayer* nvp_create(void) {
    pthread_once(&gst_init_once, gst_init_func);

    VideoPlayer* p = calloc(1, sizeof(VideoPlayer));
    if (!p) return NULL;

    pthread_mutex_init(&p->frame_lock, NULL);
    pthread_mutex_init(&p->meta_lock, NULL);
    p->volume = 1.0f;
    p->playback_speed = 1.0f;

    // Create playbin
    p->pipeline = gst_element_factory_make("playbin", NULL);
    if (!p->pipeline) {
        pthread_mutex_destroy(&p->frame_lock);
        pthread_mutex_destroy(&p->meta_lock);
        free(p);
        return NULL;
    }
    g_signal_connect(p->pipeline, "source-setup", G_CALLBACK(on_source_setup), NULL);

    // Create appsink for video frames
    p->video_sink = gst_element_factory_make("appsink", NULL);
    if (!p->video_sink) {
        gst_object_unref(p->pipeline);
        pthread_mutex_destroy(&p->frame_lock);
        pthread_mutex_destroy(&p->meta_lock);
        free(p);
        return NULL;
    }

    // Configure appsink: BGRA format for direct Skia consumption
    GstCaps* caps = gst_caps_new_simple("video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        NULL);
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), caps);
    gst_caps_unref(caps);

    // Configure appsink for media playback:
    // - sync=true: deliver frames at correct presentation time (default)
    // - drop=true: skip stale frames if app is slow to consume
    // - max-buffers=2: small buffer to smooth out jitter without adding latency
    // - emit-signals=true: callback from streaming thread
    gst_app_sink_set_emit_signals(GST_APP_SINK(p->video_sink), TRUE);
    gst_app_sink_set_drop(GST_APP_SINK(p->video_sink), TRUE);
    gst_app_sink_set_max_buffers(GST_APP_SINK(p->video_sink), 2);

    // Connect new-sample callback (called from GStreamer's streaming thread)
    g_signal_connect(p->video_sink, "new-sample", G_CALLBACK(on_new_sample), p);

    // Insert a queue before the appsink to decouple the decoder streaming
    // thread from frame extraction. This prevents jitter from the memcpy/mutex
    // in on_new_sample blocking the upstream decoder.
    GstElement* video_queue = gst_element_factory_make("queue", NULL);
    if (video_queue) {
        g_object_set(video_queue,
            "max-size-buffers", (guint)3,
            "max-size-bytes",   (guint)0,
            "max-size-time",    (guint64)0,
            NULL);

        GstBin* video_bin = GST_BIN(gst_bin_new("videobin"));
        gst_bin_add_many(video_bin, video_queue, p->video_sink, NULL);
        gst_element_link(video_queue, p->video_sink);

        // Ghost pad for the bin input
        GstPad* queue_sink = gst_element_get_static_pad(video_queue, "sink");
        GstPad* ghost_pad = gst_ghost_pad_new("sink", queue_sink);
        gst_element_add_pad(GST_ELEMENT(video_bin), ghost_pad);
        gst_object_unref(queue_sink);

        g_object_set(p->pipeline, "video-sink", video_bin, NULL);
    } else {
        // Fallback: direct appsink without queue
        g_object_set(p->pipeline, "video-sink", p->video_sink, NULL);
    }

    // Build audio bin: scaletempo -> level -> autoaudiosink
    p->audio_bin = gst_bin_new("audiobin");
    GstElement* scaletempo = gst_element_factory_make("scaletempo", NULL);
    p->level = gst_element_factory_make("level", NULL);
    GstElement* audio_sink = gst_element_factory_make("autoaudiosink", NULL);

    if (!scaletempo || !p->level || !audio_sink) {
        if (scaletempo) gst_object_unref(scaletempo);
        if (p->level) { gst_object_unref(p->level); p->level = NULL; }
        if (audio_sink) gst_object_unref(audio_sink);
        gst_object_unref(p->audio_bin);
        p->audio_bin = NULL;
    } else {
        g_object_set(p->level, "post-messages", TRUE, NULL);
        g_object_set(p->level, "interval", (guint64)(100 * GST_MSECOND), NULL);

        gst_bin_add_many(GST_BIN(p->audio_bin), scaletempo, p->level, audio_sink, NULL);
        gst_element_link_many(scaletempo, p->level, audio_sink, NULL);

        GstPad* sink_pad = gst_element_get_static_pad(scaletempo, "sink");
        GstPad* ghost = gst_ghost_pad_new("sink", sink_pad);
        gst_element_add_pad(p->audio_bin, ghost);
        gst_object_unref(sink_pad);

        g_object_set(p->pipeline, "audio-sink", p->audio_bin, NULL);
    }

    // Start bus polling thread
    p->bus_thread_running = 1;
    pthread_create(&p->bus_thread, NULL, bus_thread_func, p);

    return p;
}

void nvp_destroy(VideoPlayer* p) {
    if (!p) return;

    // Stop pipeline first — this flushes all streaming threads and ensures
    // on_new_sample will no longer be called before we free resources.
    gst_element_set_state(p->pipeline, GST_STATE_NULL);

    // Now stop bus thread (no more messages will arrive after NULL state)
    p->bus_thread_running = 0;
    pthread_join(p->bus_thread, NULL);

    gst_object_unref(p->pipeline);

    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    pthread_mutex_unlock(&p->frame_lock);
    pthread_mutex_destroy(&p->frame_lock);

    pthread_mutex_lock(&p->meta_lock);
    free(p->title);
    free(p->mime_type);
    pthread_mutex_unlock(&p->meta_lock);
    pthread_mutex_destroy(&p->meta_lock);

    free(p);
}

// ---------------------------------------------------------------------------
// Playback control
// ---------------------------------------------------------------------------

int nvp_open_uri(VideoPlayer* p, const char* uri) {
    if (!p || !uri) return 0;

    // Reset state
    gst_element_set_state(p->pipeline, GST_STATE_NULL);
    p->did_play_to_end = 0;

    // Clear old frame
    pthread_mutex_lock(&p->frame_lock);
    free(p->frame_buffer);
    p->frame_buffer = NULL;
    p->frame_width = 0;
    p->frame_height = 0;
    p->frame_size = 0;
    pthread_mutex_unlock(&p->frame_lock);

    // Clear metadata
    pthread_mutex_lock(&p->meta_lock);
    free(p->title);   p->title = NULL;
    free(p->mime_type); p->mime_type = NULL;
    p->bitrate = 0;
    p->audio_channels = 0;
    p->audio_sample_rate = 0;
    p->frame_rate = 0.0f;
    pthread_mutex_unlock(&p->meta_lock);

    // Convert raw file paths to file:// URIs if needed.
    // GStreamer playbin requires a valid URI scheme.
    gchar* resolved_uri = NULL;
    if (g_str_has_prefix(uri, "http://") || g_str_has_prefix(uri, "https://") ||
        g_str_has_prefix(uri, "rtsp://") || g_str_has_prefix(uri, "file://")) {
        resolved_uri = g_strdup(uri);
    } else {
        // Treat as a local file path — convert to file:// URI
        GError* err = NULL;
        resolved_uri = gst_filename_to_uri(uri, &err);
        if (!resolved_uri) {
            if (err) {
                g_printerr("Failed to convert path to URI: %s\n", err->message);
                g_error_free(err);
            }
            return 0;
        }
    }

    g_object_set(p->pipeline, "uri", resolved_uri, NULL);
    g_free(resolved_uri);
    g_object_set(p->pipeline, "volume", (gdouble)p->volume, NULL);

    // Pause to preroll (caller will call play() when ready)
    GstStateChangeReturn ret = gst_element_set_state(p->pipeline, GST_STATE_PAUSED);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        return 0;
    }

    return 1;
}

void nvp_play(VideoPlayer* p) {
    if (!p) return;
    g_object_set(p->pipeline, "volume", (gdouble)p->volume, NULL);
    gst_element_set_state(p->pipeline, GST_STATE_PLAYING);
}

void nvp_pause(VideoPlayer* p) {
    if (!p) return;
    gst_element_set_state(p->pipeline, GST_STATE_PAUSED);
}

void nvp_set_volume(VideoPlayer* p, float volume) {
    if (!p) return;
    p->volume = volume;
    g_object_set(p->pipeline, "volume", (gdouble)volume, NULL);
}

float nvp_get_volume(VideoPlayer* p) {
    return p ? p->volume : 0.0f;
}

void nvp_seek_to(VideoPlayer* p, double time_seconds) {
    if (!p) return;
    gint64 pos = (gint64)(time_seconds * GST_SECOND);
    gst_element_seek(p->pipeline,
        (gdouble)p->playback_speed,
        GST_FORMAT_TIME,
        GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
        GST_SEEK_TYPE_SET, pos,
        GST_SEEK_TYPE_NONE, -1);
}

void nvp_set_playback_speed(VideoPlayer* p, float speed) {
    if (!p) return;
    p->playback_speed = speed;

    gint64 pos = 0;
    if (gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &pos)) {
        gst_element_seek(p->pipeline,
            (gdouble)speed,
            GST_FORMAT_TIME,
            GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
            GST_SEEK_TYPE_SET, pos,
            GST_SEEK_TYPE_NONE, -1);
    }
}

float nvp_get_playback_speed(VideoPlayer* p) {
    return p ? p->playback_speed : 1.0f;
}

// ---------------------------------------------------------------------------
// Frame access
// ---------------------------------------------------------------------------

void* nvp_get_latest_frame_address(VideoPlayer* p) {
    if (!p) return NULL;
    return p->frame_buffer;
}

int32_t nvp_get_frame_width(VideoPlayer* p) {
    return p ? p->frame_width : 0;
}

int32_t nvp_get_frame_height(VideoPlayer* p) {
    return p ? p->frame_height : 0;
}

int32_t nvp_set_output_size(VideoPlayer* p, int32_t width, int32_t height) {
    if (!p || width <= 0 || height <= 0) return 0;
    p->output_width = width;
    p->output_height = height;

    GstCaps* caps = gst_caps_new_simple("video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        "width", G_TYPE_INT, (gint)width,
        "height", G_TYPE_INT, (gint)height,
        NULL);
    gst_app_sink_set_caps(GST_APP_SINK(p->video_sink), caps);
    gst_caps_unref(caps);
    return 1;
}

// ---------------------------------------------------------------------------
// Timing
// ---------------------------------------------------------------------------

double nvp_get_duration(VideoPlayer* p) {
    if (!p) return 0.0;
    gint64 dur = 0;
    if (gst_element_query_duration(p->pipeline, GST_FORMAT_TIME, &dur) && dur > 0) {
        return (double)dur / (double)GST_SECOND;
    }
    return 0.0;
}

double nvp_get_current_time(VideoPlayer* p) {
    if (!p) return 0.0;
    gint64 pos = 0;
    if (gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &pos) && pos >= 0) {
        return (double)pos / (double)GST_SECOND;
    }
    return 0.0;
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------

char* nvp_get_title(VideoPlayer* p) {
    if (!p) return NULL;
    pthread_mutex_lock(&p->meta_lock);
    char* result = p->title ? strdup(p->title) : NULL;
    pthread_mutex_unlock(&p->meta_lock);
    return result;
}

int64_t nvp_get_bitrate(VideoPlayer* p) {
    return p ? p->bitrate : 0;
}

char* nvp_get_mime_type(VideoPlayer* p) {
    if (!p) return NULL;
    pthread_mutex_lock(&p->meta_lock);
    char* result = p->mime_type ? strdup(p->mime_type) : NULL;
    pthread_mutex_unlock(&p->meta_lock);
    return result;
}

int32_t nvp_get_audio_channels(VideoPlayer* p) {
    return p ? p->audio_channels : 0;
}

int32_t nvp_get_audio_sample_rate(VideoPlayer* p) {
    return p ? p->audio_sample_rate : 0;
}

float nvp_get_frame_rate(VideoPlayer* p) {
    return p ? p->frame_rate : 0.0f;
}

// ---------------------------------------------------------------------------
// End-of-stream
// ---------------------------------------------------------------------------

int32_t nvp_consume_did_play_to_end(VideoPlayer* p) {
    if (!p) return 0;
    int val = __sync_lock_test_and_set(&p->did_play_to_end, 0);
    return val;
}

// ---------------------------------------------------------------------------
// Internal: new-sample callback (called from GStreamer streaming thread)
// ---------------------------------------------------------------------------

static GstFlowReturn on_new_sample(GstAppSink* sink, gpointer data) {
    VideoPlayer* p = (VideoPlayer*)data;

    GstSample* sample = gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_OK;

    GstCaps* caps = gst_sample_get_caps(sample);
    if (!caps) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    GstStructure* s = gst_caps_get_structure(caps, 0);
    gint width = 0, height = 0;
    gst_structure_get_int(s, "width", &width);
    gst_structure_get_int(s, "height", &height);

    if (width <= 0 || height <= 0) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    // Extract frame rate from caps
    gint fps_n = 0, fps_d = 1;
    if (gst_structure_get_fraction(s, "framerate", &fps_n, &fps_d) && fps_d > 0) {
        p->frame_rate = (float)fps_n / (float)fps_d;
    }

    GstBuffer* buffer = gst_sample_get_buffer(sample);
    if (!buffer) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    size_t expected = (size_t)width * (size_t)height * 4;
    if (map.size < expected) {
        gst_buffer_unmap(buffer, &map);
        gst_sample_unref(sample);
        return GST_FLOW_OK;
    }

    pthread_mutex_lock(&p->frame_lock);

    if (p->frame_width != width || p->frame_height != height || !p->frame_buffer) {
        free(p->frame_buffer);
        p->frame_buffer = (uint8_t*)malloc(expected);
        if (!p->frame_buffer) {
            p->frame_width = 0;
            p->frame_height = 0;
            p->frame_size = 0;
            pthread_mutex_unlock(&p->frame_lock);
            gst_buffer_unmap(buffer, &map);
            gst_sample_unref(sample);
            return GST_FLOW_OK;
        }
        p->frame_width = width;
        p->frame_height = height;
        p->frame_size = expected;
    }

    memcpy(p->frame_buffer, map.data, expected);

    pthread_mutex_unlock(&p->frame_lock);

    gst_buffer_unmap(buffer, &map);
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

// ---------------------------------------------------------------------------
// Internal: metadata extraction from tags
// ---------------------------------------------------------------------------

static void update_metadata_from_tags(VideoPlayer* p, GstTagList* tags) {
    gchar* str = NULL;

    pthread_mutex_lock(&p->meta_lock);

    if (gst_tag_list_get_string(tags, GST_TAG_TITLE, &str)) {
        free(p->title);
        p->title = strdup(str);
        g_free(str);
    }

    guint bitrate = 0;
    if (gst_tag_list_get_uint(tags, GST_TAG_BITRATE, &bitrate) ||
        gst_tag_list_get_uint(tags, GST_TAG_NOMINAL_BITRATE, &bitrate)) {
        p->bitrate = (int64_t)bitrate;
    }

    str = NULL;
    if (gst_tag_list_get_string(tags, GST_TAG_CONTAINER_FORMAT, &str)) {
        free(p->mime_type);
        p->mime_type = strdup(str);
        g_free(str);
    } else if (gst_tag_list_get_string(tags, GST_TAG_AUDIO_CODEC, &str)) {
        if (!p->mime_type) {
            p->mime_type = strdup(str);
        }
        g_free(str);
    } else if (gst_tag_list_get_string(tags, GST_TAG_VIDEO_CODEC, &str)) {
        if (!p->mime_type) {
            p->mime_type = strdup(str);
        }
        g_free(str);
    }

    pthread_mutex_unlock(&p->meta_lock);
}

// ---------------------------------------------------------------------------
// Internal: stream metadata from pads (channels, sample rate, resolution)
// ---------------------------------------------------------------------------

static void update_stream_metadata(VideoPlayer* p) {
    // Video info from appsink pad
    GstPad* vpad = gst_element_get_static_pad(p->video_sink, "sink");
    if (vpad) {
        GstCaps* vcaps = gst_pad_get_current_caps(vpad);
        if (vcaps && gst_caps_get_size(vcaps) > 0) {
            GstStructure* vs = gst_caps_get_structure(vcaps, 0);
            gint w = 0, h = 0;
            gst_structure_get_int(vs, "width", &w);
            gst_structure_get_int(vs, "height", &h);

            // Only update dimensions if not already set by frame callback
            if (w > 0 && h > 0 && (p->frame_width == 0 || p->frame_height == 0)) {
                pthread_mutex_lock(&p->frame_lock);
                if (p->frame_width == 0 || p->frame_height == 0) {
                    p->frame_width = w;
                    p->frame_height = h;
                }
                pthread_mutex_unlock(&p->frame_lock);
            }

            gint fps_n = 0, fps_d = 1;
            if (gst_structure_get_fraction(vs, "framerate", &fps_n, &fps_d) && fps_d > 0) {
                p->frame_rate = (float)fps_n / (float)fps_d;
            }
        }
        if (vcaps) gst_caps_unref(vcaps);
        gst_object_unref(vpad);
    }

    // Audio info from the level element's sink pad
    if (p->level) {
        GstPad* apad = gst_element_get_static_pad(p->level, "sink");
        if (apad) {
            GstCaps* acaps = gst_pad_get_current_caps(apad);
            if (acaps && gst_caps_get_size(acaps) > 0) {
                GstStructure* as_ = gst_caps_get_structure(acaps, 0);
                gint channels = 0, rate = 0;
                if (gst_structure_get_int(as_, "channels", &channels)) {
                    p->audio_channels = channels;
                }
                if (gst_structure_get_int(as_, "rate", &rate)) {
                    p->audio_sample_rate = rate;
                }
            }
            if (acaps) gst_caps_unref(acaps);
            gst_object_unref(apad);
        }
    }
}
