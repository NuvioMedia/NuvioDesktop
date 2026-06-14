//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_COMPETIBLE_THREAD_H
#define MEDIAMP_COMPETIBLE_THREAD_H

#include <functional>

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#else
#include <pthread.h>
#endif

namespace mediampv {

class compatible_thread {
public:
    template<typename Callable>
    explicit compatible_thread(Callable &&func)
            : entry_(std::forward<Callable>(func)) {}

    // Disable copy constructor and copy assignment
    compatible_thread(const compatible_thread &) = delete;

    compatible_thread &operator=(const compatible_thread &) = delete;

    // Move semantics (optional if needed)
    compatible_thread(compatible_thread &&) = default;

    compatible_thread &operator=(compatible_thread &&) = default;
    
    bool create();
    void join();

private:
#if defined(_WIN32) || defined(_WIN64)
    static DWORD WINAPI entry(LPVOID param) {
        auto* self = static_cast<compatible_thread*>(param);
        self->entry_();
        return 0;
    };

    HANDLE handle_{ nullptr };
    DWORD thread_id_{ 0 };
#else
    static void *entry(void *param) {
        auto *self = static_cast<compatible_thread *>(param);
        self->entry_();
        return nullptr;
    };

    pthread_t handle_{};
#endif

    std::function<void()> entry_;
};

} // namespace mediampv

#endif //MEDIAMP_COMPETIBLE_THREAD_H