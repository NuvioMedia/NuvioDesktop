#include "compatible_thread.h"

namespace mediampv {

bool compatible_thread::create() {
#if defined(_WIN32) || defined(_WIN64)
    handle_ = CreateThread(
        nullptr,         // default security attributes
        0,               // default stack size
        &entry,          // entry point (static function)
        this,            // pass 'this' as the parameter
        0,               // default creation flags
        &thread_id_      // receive thread identifier
    );
    return (handle_ != nullptr);
#else
    return (pthread_create(&handle_, nullptr, &entry, this) == 0);
#endif
}
 
void compatible_thread::join() {
#if defined(_WIN32) || defined(_WIN64)
    if (handle_) {
        WaitForSingleObject(handle_, INFINITE);
        CloseHandle(handle_);
        handle_ = nullptr;
    }
#else
    pthread_join(handle_, nullptr);
#endif
}

} // namespace mediampv