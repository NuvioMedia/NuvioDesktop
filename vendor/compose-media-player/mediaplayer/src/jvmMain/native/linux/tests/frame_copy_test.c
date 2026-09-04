#include "NativeVideoPlayer.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(condition) \
    do { \
        if (!(condition)) { \
            fprintf(stderr, "CHECK failed at %s:%d: %s\n", __FILE__, __LINE__, #condition); \
            exit(EXIT_FAILURE); \
        } \
    } while (0)

static void source_and_destination_padding_are_respected(void) {
    enum {
        WIDTH = 2,
        HEIGHT = 2,
        PIXEL_BYTES = 4,
        SOURCE_STRIDE = 12,
        DESTINATION_STRIDE = 16,
    };

    const uint8_t source[SOURCE_STRIDE * HEIGHT] = {
        1, 2, 3, 4, 5, 6, 7, 8, 0xee, 0xee, 0xee, 0xee,
        9, 10, 11, 12, 13, 14, 15, 16, 0xdd, 0xdd, 0xdd, 0xdd,
    };
    uint8_t destination[DESTINATION_STRIDE * HEIGHT];
    memset(destination, 0xa5, sizeof(destination));

    VideoPlayer* player = nvp_create();
    CHECK(player != NULL);
    CHECK(nvp_test_publish_bgra(
        player,
        source,
        WIDTH,
        HEIGHT,
        SOURCE_STRIDE
    ) == 1);

    NvpFrameInfo info = {0};
    const int32_t status = nvp_copy_latest_frame(
        player,
        destination,
        sizeof(destination),
        WIDTH,
        HEIGHT,
        DESTINATION_STRIDE,
        &info
    );

    CHECK(status == NVP_FRAME_COPY_OK);
    CHECK(info.width == WIDTH);
    CHECK(info.height == HEIGHT);
    CHECK(info.source_stride == WIDTH * PIXEL_BYTES);

    for (int row = 0; row < HEIGHT; ++row) {
        CHECK(memcmp(
            destination + row * DESTINATION_STRIDE,
            source + row * SOURCE_STRIDE,
            WIDTH * PIXEL_BYTES
        ) == 0);
        for (int index = WIDTH * PIXEL_BYTES; index < DESTINATION_STRIDE; ++index) {
            CHECK(destination[row * DESTINATION_STRIDE + index] == 0xa5);
        }
    }

    nvp_destroy(player);
}

typedef struct CopyThreadArgs {
    VideoPlayer* player;
    uint8_t destination[16];
    int32_t status;
} CopyThreadArgs;

static void* copy_thread(void* opaque) {
    CopyThreadArgs* args = (CopyThreadArgs*)opaque;
    NvpFrameInfo info = {0};
    args->status = nvp_copy_latest_frame(
        args->player,
        args->destination,
        sizeof(args->destination),
        2,
        2,
        8,
        &info
    );
    return NULL;
}

typedef struct PublishThreadArgs {
    VideoPlayer* player;
    atomic_int finished;
} PublishThreadArgs;

static void* publish_thread(void* opaque) {
    PublishThreadArgs* args = (PublishThreadArgs*)opaque;
    const uint8_t replacement[] = {21, 22, 23, 24};
    CHECK(nvp_test_publish_bgra(args->player, replacement, 1, 1, 4) == 1);
    atomic_store(&args->finished, 1);
    return NULL;
}

static void copy_blocks_resize_publication(void) {
    const uint8_t initial[] = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16,
    };
    VideoPlayer* player = nvp_create();
    CHECK(player != NULL);
    CHECK(nvp_test_publish_bgra(player, initial, 2, 2, 8) == 1);

    nvp_test_pause_next_copy(player);
    CopyThreadArgs copy_args = {
        .player = player,
        .destination = {0},
        .status = NVP_FRAME_COPY_INVALID,
    };
    pthread_t copier;
    CHECK(pthread_create(&copier, NULL, copy_thread, &copy_args) == 0);
    nvp_test_wait_until_copy_paused(player);
    CHECK(nvp_test_try_frame_lock(player) == 0);

    PublishThreadArgs publish_args = {
        .player = player,
        .finished = ATOMIC_VAR_INIT(0),
    };
    nvp_test_arm_publish_attempt(player);
    pthread_t publisher;
    CHECK(pthread_create(&publisher, NULL, publish_thread, &publish_args) == 0);
    CHECK(nvp_test_wait_until_publish_attempted(player) == EBUSY);
    CHECK(atomic_load(&publish_args.finished) == 0);

    nvp_test_resume_copy(player);
    CHECK(pthread_join(copier, NULL) == 0);
    CHECK(pthread_join(publisher, NULL) == 0);
    CHECK(copy_args.status == NVP_FRAME_COPY_OK);
    CHECK(memcmp(copy_args.destination, initial, sizeof(initial)) == 0);

    uint8_t replacement_destination[4] = {0};
    NvpFrameInfo replacement_info = {0};
    CHECK(nvp_copy_latest_frame(
        player,
        replacement_destination,
        sizeof(replacement_destination),
        1,
        1,
        4,
        &replacement_info
    ) == NVP_FRAME_COPY_OK);
    const uint8_t expected_replacement[] = {21, 22, 23, 24};
    CHECK(memcmp(
        replacement_destination,
        expected_replacement,
        sizeof(expected_replacement)
    ) == 0);

    nvp_destroy(player);
}

static void invalid_layouts_fail_without_writing_destination(void) {
    const uint8_t source[16] = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16,
    };
    VideoPlayer* player = nvp_create();
    CHECK(player != NULL);
    CHECK(nvp_test_publish_bgra(player, source, 2, 2, 7) == 0);
    CHECK(nvp_test_publish_bgra(player, source, 2, 2, 8) == 1);

    uint8_t destination[16];
    memset(destination, 0x5a, sizeof(destination));
    NvpFrameInfo info = {0};
    CHECK(nvp_copy_latest_frame(
        player,
        destination,
        sizeof(destination),
        1,
        1,
        4,
        &info
    ) == NVP_FRAME_COPY_SIZE_CHANGED);
    CHECK(info.width == 2);
    CHECK(info.height == 2);
    for (size_t index = 0; index < sizeof(destination); ++index) {
        CHECK(destination[index] == 0x5a);
    }

    CHECK(nvp_copy_latest_frame(
        player,
        destination,
        sizeof(destination) - 1,
        2,
        2,
        8,
        &info
    ) == NVP_FRAME_COPY_DEST_TOO_SMALL);
    for (size_t index = 0; index < sizeof(destination); ++index) {
        CHECK(destination[index] == 0x5a);
    }

    nvp_destroy(player);
}

int main(void) {
    source_and_destination_padding_are_respected();
    copy_blocks_resize_publication();
    invalid_layouts_fail_without_writing_destination();
    puts("frame_copy_test: passed");
    return 0;
}
