cmake_minimum_required(VERSION 3.21)

project("mediampv" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# set prebuilt mpv library path for each target

set(MPV_LIB_PATH "")
set(MPV_LIB_NAME "")

if(WIN32)
    add_compile_definitions(_WIN32_WINNT=0x0A00 NOMINMAX)
    # Windows
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        # x64
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/windows/x86_64")
        set(MPV_LIB_NAME "libmpv-2.lib")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "avcodec.lib")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "swscale.lib")
    else()
        message(FATAL_ERROR "only support x86_64 on Windows")
    endif()

    if (MSVC)
        message(STATUS "MSVC detected")
        add_compile_options(/Zc:__cplusplus)
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
    endif ()
elseif(APPLE)
    # macOS (Darwin)
    if((CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64") OR (CMAKE_SYSTEM_PROCESSOR STREQUAL "arm64"))
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/macos/${CMAKE_SYSTEM_PROCESSOR}")
        set(MPV_LIB_NAME "libmpv.2.dylib")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "libavcodec.62.11.100.dylib")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "libswscale.9.1.100.dylib")
    endif()
elseif(ANDROID)
    set(ANDROID_STL c++_shared)
    set(CMAKE_SYSTEM_NAME Android)
    set(CMAKE_SYSTEM_VERSION 21) # Adjust as needed

    # Set Android NDK paths
    set(ANDROID_NDK ${ANDROID_NDK_HOME})
    set(CMAKE_ANDROID_NDK ${ANDROID_NDK})
    set(CMAKE_TOOLCHAIN_FILE ${ANDROID_NDK}/build/cmake/android.toolchain.cmake)

    if ((CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
        OR (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        OR (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    )
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/android/${CMAKE_ANDROID_ARCH_ABI}")
        set(MPV_LIB_NAME "libmpv.so")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "libavcodec.so")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "libswscale.so")
    endif()
elseif(UNIX AND NOT APPLE)
    # Linux
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        # First try to find system libraries, fallback to bundled
        find_library(LIBMPV_LIB NAMES mpv PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBAVCODEC_LIB NAMES avcodec PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBAVFORMAT_LIB NAMES avformat PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBAVUTIL_LIB NAMES avutil PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBSWSCALE_LIB NAMES swscale PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBSWRESAMPLE_LIB NAMES swresample PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        find_library(LIBAVFILTER_LIB NAMES avfilter PATHS /usr/lib/x86_64-linux-gnu /usr/lib64 /usr/lib NO_DEFAULT_PATH QUIET)
        
        if(NOT LIBMPV_LIB)
            # Fallback to bundled libraries
            set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/linux/x86_64")
            set(MPV_LIB_NAME "libmpv.so")
            set(MPV_LIB_EXTERNAL_AVCODEC_NAME "libavcodec.so")
            set(MPV_LIB_EXTERNAL_SWSCALE_NAME "libswscale.so")
        else()
            # Use system libraries - set dummy names since we'll use full paths
            set(MPV_LIB_PATH "")
            set(MPV_LIB_NAME "")
            set(MPV_LIB_EXTERNAL_AVCODEC_NAME "")
            set(MPV_LIB_EXTERNAL_SWSCALE_NAME "")
            set(MPV_LIB_EXTERNAL_AVFORMAT_NAME "")
            set(MPV_LIB_EXTERNAL_AVUTIL_NAME "")
            set(MPV_LIB_EXTERNAL_SWRESAMPLE_NAME "")
            set(MPV_LIB_EXTERNAL_AVFILTER_NAME "")
        endif()
    else()
        message(FATAL_ERROR "only support x86_64 on Linux")
    endif()
else()
    message(FATAL_ERROR "Unknown operating system")
endif()

# configure project

if (NOT ANDROID) 
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

set(SRC_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/src/cpp")

add_library(${PROJECT_NAME} SHARED
    "${SRC_PREFIX}/main.cpp"
    "${SRC_PREFIX}/include/log.h"
    "${SRC_PREFIX}/include/global_lock.h"
    "${SRC_PREFIX}/include/compatible_thread.h"
    "${SRC_PREFIX}/compatible_thread.cpp"
    "${SRC_PREFIX}/include/method_cache.h"
    "${SRC_PREFIX}/method_cache.cpp"
    "${SRC_PREFIX}/event_listener.cpp"
    "${SRC_PREFIX}/include/mpv_handle_t.h"
    "${SRC_PREFIX}/mpv_handle_t.cpp"
    "${SRC_PREFIX}/jni.cpp"
)
target_include_directories(${PROJECT_NAME} PRIVATE "${SRC_PREFIX}/include")

## link project
if (ANDROID)
    target_link_libraries(${PROJECT_NAME} PRIVATE 
            android log
            "${MPV_LIB_PATH}/${MPV_LIB_NAME}")
else()
    target_link_libraries(${PROJECT_NAME} PRIVATE
            JNI::JNI)
    
    if(WIN32)
        target_link_libraries(${PROJECT_NAME} PRIVATE
                opengl32)
    elseif(APPLE)
        target_link_libraries(${PROJECT_NAME} PRIVATE
                "-framework OpenGL"
                "-framework Cocoa")
    elseif(UNIX)
        target_link_libraries(${PROJECT_NAME} PRIVATE
                EGL GL dl)
    endif()
endif()

# NuvioImageBridge — Windows WIC-based image decoder (Coil 3 integration)
option(BUILD_NUVIO_IMAGE_BRIDGE "Build the NuvioImageBridge DLL for Windows image decoding" OFF)

if(WIN32 AND BUILD_NUVIO_IMAGE_BRIDGE)
    add_library(NuvioImageBridge SHARED
        "${SRC_PREFIX}/nuvio_image_bridge.cpp"
    )
    target_link_libraries(NuvioImageBridge PRIVATE
        windowscodecs.lib
        ole32.lib
    )
    set_target_properties(NuvioImageBridge PROPERTIES
        MSVC_RUNTIME_LIBRARY "MultiThreaded"
    )
    set(NUVIO_BRIDGE_OUTPUT_DIR $<TARGET_FILE_DIR:${PROJECT_NAME}>)
    add_custom_command(TARGET NuvioImageBridge POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E echo "Copying NuvioImageBridge.dll to output..."
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:NuvioImageBridge>
            ${NUVIO_BRIDGE_OUTPUT_DIR}
    )
endif()

# 查找所有动态库文件
file(GLOB MPV_LIBS_DEPS
    "${MPV_LIB_PATH}/*.dylib"  # macOS 动态库
    "${MPV_LIB_PATH}/*.so"     # Linux 和 Android 动态库
    "${MPV_LIB_PATH}/*.dll"    # Windows 动态库
)

set(MPV_OUTPUT_LIBS_DIR "")
if (WIN32 OR ANDROID)
    set(MPV_OUTPUT_LIBS_DIR $<TARGET_FILE_DIR:${PROJECT_NAME}>)
else ()
    set(MPV_OUTPUT_LIBS_DIR "$<TARGET_FILE_DIR:${PROJECT_NAME}>/deps/")
endif ()

# Copy each dep individually to avoid long command lines on Windows
foreach(dep ${MPV_LIBS_DEPS})
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E echo "Copying ${dep}..."
        COMMAND ${CMAKE_COMMAND} -E make_directory ${MPV_OUTPUT_LIBS_DIR}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${dep}
            ${MPV_OUTPUT_LIBS_DIR}
    )
endforeach()


message(STATUS "MPV library path: ${MPV_LIB_PATH}")
message(STATUS "MPV library name: ${MPV_LIB_NAME}")
