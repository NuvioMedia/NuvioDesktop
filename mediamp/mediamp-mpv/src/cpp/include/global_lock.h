//
// Created by StageGuard on 12/29/2024.
//

#ifndef MEDIAMP_GLOBAL_LOCK_H
#define MEDIAMP_GLOBAL_LOCK_H

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>

class CompatibleLock {
public:
    CompatibleLock() { InitializeCriticalSection(&cs_); }
    ~CompatibleLock() { DeleteCriticalSection(&cs_); }
    void lock() { EnterCriticalSection(&cs_); }
    void unlock() { LeaveCriticalSection(&cs_); }
private:
    CRITICAL_SECTION cs_;
};

// RAII
class LockGuard {
public:
    LockGuard(CompatibleLock& lock) : lock_(lock) { lock_.lock(); }
    ~LockGuard() { lock_.unlock();  }
private:
    CompatibleLock& lock_;
};

#define CREATE_LOCK(lock_name) CompatibleLock lock_name
#define LOCK(lock_name) LockGuard guard_##lock_name(lock_name)

#else
#include <mutex>

#define CREATE_LOCK(lock_name) std::mutex lock_name
#define LOCK(lock_name) std::lock_guard<std::mutex> guard_##lock_name(lock_name)

#endif

#endif //MEDIAMP_GLOBAL_LOCK_H
