// Copyright (C) 2024-2026 OpenAni and contributors.
//
// Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
//
// https://github.com/open-ani/mediamp/blob/main/LICENSE

//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_LOG_H
#define MEDIAMP_LOG_H

#define ENABLE_LOGGING

#ifdef ENABLE_LOGGING
#define LOG_TAG "mediampv"
#if (defined(__APPLE__) && defined(__MACH__)) || (defined(__linux__) && !defined(__ANDROID__)) || (defined(_WIN32) || defined(_WIN64))
#include <cstdio>
#define LOG(...) do { printf(__VA_ARGS__); fflush(stdout); } while(0)
#else
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#endif // defined(__APPLE__) && defined(__MACH__) || (defined(__linux__) && !defined(__ANDROID__))
#else
#define LOG(...) ((void *) 0)
#endif // ENABLE_LOGGING

#include <iostream>
struct function_printer_t {
#ifdef ENABLE_LOGGING
std::string name;
explicit function_printer_t(const std::string &name) : name(name) {
LOG("Function %s started\n", name.c_str());
}
#else
explicit function_printer_t(const std::string &_) {
}
#endif

~function_printer_t() {
#ifdef ENABLE_LOGGING
LOG("Function %s ended\n", name.c_str());
#endif
}
};

#define FP function_printer_t _fp(__FUNCTION__)

#endif //MEDIAMP_LOG_H
