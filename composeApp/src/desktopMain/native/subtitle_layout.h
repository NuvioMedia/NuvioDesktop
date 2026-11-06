#pragma once

#include "macos/include/mpv/client.h"
#include <initializer_list>

constexpr uint64_t NUVIO_SUBTITLE_LAYOUT_EVENT = 3100;

inline void nuvioObserveSubtitleLayout(mpv_handle *mpv, decltype(&mpv_observe_property) observe) {
    for (const char *name : {"sub-text", "secondary-sub-text", "sub-pos", "sub-scale", "sub-font-size", "sub-font",
                            "sub-bold", "sub-italic", "sub-margin-x", "sub-margin-y", "sub-outline-size",
                            "sub-color", "sub-outline-color", "sub-back-color", "sub-shadow-color", "sub-shadow-offset",
                            "sub-border-style", "sub-fonts-dir"}) {
        observe(mpv, NUVIO_SUBTITLE_LAYOUT_EVENT, name, MPV_FORMAT_STRING);
    }
    observe(mpv, NUVIO_SUBTITLE_LAYOUT_EVENT, "osd-dimensions", MPV_FORMAT_NODE);
}
