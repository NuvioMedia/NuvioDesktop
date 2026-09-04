cmake_minimum_required(VERSION 3.15)
project(NativeVideoPlayer C)

set(CMAKE_C_STANDARD 11)

# Find GStreamer via pkg-config
find_package(PkgConfig REQUIRED)
pkg_check_modules(GST REQUIRED
    gstreamer-1.0
    gstreamer-app-1.0
    gstreamer-video-1.0
)

# Find JNI
find_package(JNI REQUIRED)

# Source files
set(SOURCES
    NativeVideoPlayer.c
    jni_bridge.c
)

add_library(NativeVideoPlayer SHARED ${SOURCES})

target_include_directories(NativeVideoPlayer PRIVATE
    ${GST_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(NativeVideoPlayer PRIVATE
    ${GST_LIBRARIES}
    m
    pthread
)

target_compile_options(NativeVideoPlayer PRIVATE
    ${GST_CFLAGS_OTHER}
    -Wall -Wextra -Wno-unused-parameter
    -O2
    -fPIC
)

include(CTest)
if(BUILD_TESTING)
    add_executable(frame_copy_test
        tests/frame_copy_test.c
        NativeVideoPlayer.c
    )
    target_compile_definitions(frame_copy_test PRIVATE NVP_TESTING)
    target_include_directories(frame_copy_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${GST_INCLUDE_DIRS}
    )
    target_link_libraries(frame_copy_test PRIVATE
        ${GST_LIBRARIES}
        m
        pthread
    )
    target_compile_options(frame_copy_test PRIVATE
        ${GST_CFLAGS_OTHER}
        -Wall -Wextra -Werror
        -O2
    )
    add_test(NAME frame_copy_test COMMAND frame_copy_test)
    set_tests_properties(frame_copy_test PROPERTIES TIMEOUT 30)
endif()

# Determine output directory based on architecture
if(DEFINED ENV{NATIVE_LIBS_OUTPUT_DIR})
    set(BASE_OUTPUT_DIR "$ENV{NATIVE_LIBS_OUTPUT_DIR}")
else()
    set(BASE_OUTPUT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../resources/composemediaplayer/native")
endif()

execute_process(COMMAND uname -m OUTPUT_VARIABLE ARCH OUTPUT_STRIP_TRAILING_WHITESPACE)
if(ARCH STREQUAL "x86_64" OR ARCH STREQUAL "amd64")
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-x86-64")
elseif(ARCH STREQUAL "aarch64" OR ARCH STREQUAL "arm64")
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-aarch64")
else()
    set(RESOURCE_DIR "${BASE_OUTPUT_DIR}/linux-${ARCH}")
endif()

# Copy library to resources after build
add_custom_command(TARGET NativeVideoPlayer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${RESOURCE_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:NativeVideoPlayer>" "${RESOURCE_DIR}/"
    COMMENT "Copying libNativeVideoPlayer.so to ${RESOURCE_DIR}"
)
