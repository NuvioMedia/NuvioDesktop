use std::ffi::c_void;
use std::os::raw::{c_char, c_int};
use std::sync::OnceLock;

use libloading::Library;

#[cfg(windows)]
extern "system" {
    fn GetModuleHandleA(name: *const i8) -> *mut c_void;
    fn GetModuleFileNameA(module: *mut c_void, filename: *mut i8, size: u32) -> u32;
}

pub const GL_TEXTURE_2D: i32 = 0x0DE1;
pub const GL_RGBA8: i32 = 0x8058;
pub const GL_FRAMEBUFFER: i32 = 0x8D40;
pub const GL_COLOR_ATTACHMENT0: i32 = 0x8CE0;
pub const GL_COLOR_BUFFER_BIT: i32 = 0x00004000;
pub const GL_RGBA: i32 = 0x1908;
pub const GL_UNSIGNED_BYTE: i32 = 0x1401;

pub const MPV_RENDER_PARAM_INVALID: i32 = 0;
pub const MPV_RENDER_PARAM_API_TYPE: i32 = 1;
pub const MPV_RENDER_PARAM_OPENGL_INIT_PARAMS: i32 = 2;
pub const MPV_RENDER_PARAM_OPENGL_FBO: i32 = 3;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
#[allow(non_camel_case_types)]
pub enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
    MPV_FORMAT_NODE = 6,
    MPV_FORMAT_NODE_ARRAY = 7,
    MPV_FORMAT_NODE_MAP = 8,
    MPV_FORMAT_BYTE_ARRAY = 9,
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
#[allow(non_camel_case_types)]
pub enum mpv_event_id {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_SHUTDOWN = 1,
    MPV_EVENT_LOG_MESSAGE = 2,
    MPV_EVENT_GET_PROPERTY_REPLY = 3,
    MPV_EVENT_SET_PROPERTY_REPLY = 4,
    MPV_EVENT_COMMAND_REPLY = 5,
    MPV_EVENT_START_FILE = 6,
    MPV_EVENT_END_FILE = 7,
    MPV_EVENT_FILE_LOADED = 8,
    MPV_EVENT_IDLE = 11,
    MPV_EVENT_TICK = 14,
    MPV_EVENT_CLIENT_MESSAGE = 16,
    MPV_EVENT_VIDEO_RECONFIG = 17,
    MPV_EVENT_AUDIO_RECONFIG = 18,
    MPV_EVENT_SEEK = 20,
    MPV_EVENT_PLAYBACK_RESTART = 21,
    MPV_EVENT_PROPERTY_CHANGE = 22,
    MPV_EVENT_QUEUE_OVERFLOW = 24,
    MPV_EVENT_HOOK = 25,
}

#[repr(C)]
pub struct mpv_opengl_fbo {
    pub fbo: c_int,
    pub w: c_int,
    pub h: c_int,
    pub internal_format: c_int,
}

pub type GlGetProcAddrFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;

#[repr(C)]
pub struct mpv_opengl_init_params {
    pub get_proc_address: Option<GlGetProcAddrFn>,
    pub get_proc_address_ctx: *mut c_void,
}

#[repr(C)]
pub struct mpv_render_param {
    pub type_: i32,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct mpv_event_property {
    pub name: *const c_char,
    pub format: mpv_format,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct mpv_event_end_file {
    pub playlist_entry_id: i64,
    pub reason: c_int,
    pub error: c_int,
}

#[repr(C)]
pub struct mpv_event {
    pub event_id: mpv_event_id,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

pub struct MpvFunctions {
    pub mpv_create: unsafe extern "C" fn() -> *mut c_void,
    pub mpv_initialize: unsafe extern "C" fn(*mut c_void) -> c_int,
    pub mpv_destroy: unsafe extern "C" fn(*mut c_void),
    pub mpv_terminate_destroy: unsafe extern "C" fn(*mut c_void),
    pub mpv_command: unsafe extern "C" fn(*mut c_void, *const *const c_char) -> c_int,
    pub mpv_set_option_string: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> c_int,
    pub mpv_get_property: unsafe extern "C" fn(*mut c_void, *const c_char, mpv_format, *mut c_void) -> c_int,
    pub mpv_set_property: unsafe extern "C" fn(*mut c_void, *const c_char, mpv_format, *const c_void) -> c_int,
    pub mpv_get_property_string: unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_char,
    pub mpv_free: unsafe extern "C" fn(*mut c_void),
    pub mpv_observe_property: unsafe extern "C" fn(*mut c_void, u64, *const c_char, mpv_format) -> c_int,
    pub mpv_unobserve_property: unsafe extern "C" fn(*mut c_void, u64) -> c_int,
    pub mpv_error_string: unsafe extern "C" fn(c_int) -> *const c_char,
    pub mpv_wait_event: unsafe extern "C" fn(*mut c_void, f64) -> *mut mpv_event,
    pub mpv_wakeup: unsafe extern "C" fn(*mut c_void),
    pub mpv_set_wakeup_callback: unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
    pub mpv_render_context_create: unsafe extern "C" fn(*mut *mut c_void, *mut c_void, *const mpv_render_param) -> c_int,
    pub mpv_render_context_free: unsafe extern "C" fn(*mut c_void),
    pub mpv_render_context_update: Option<unsafe extern "C" fn(*mut c_void) -> u64>,
    pub mpv_render_context_render: unsafe extern "C" fn(*mut c_void, *const mpv_render_param) -> c_int,
    pub mpv_render_context_set_update_callback: unsafe extern "C" fn(*mut c_void, Option<unsafe extern "C" fn(*mut c_void)>, *mut c_void),
}

static MPV_RESULT: OnceLock<Result<&'static MpvFunctions, String>> = OnceLock::new();

unsafe fn load_fn<T>(lib: &Library, name: &str) -> T {
    let cname = std::ffi::CString::new(name).unwrap();
    let sym: libloading::Symbol<T> = lib.get(cname.as_bytes()).unwrap();
    let ptr: *const T = &*sym;
    std::ptr::read(ptr)
}

fn load_mpv_inner() -> Result<MpvFunctions, String> {

    let try_load = |name: &str| -> Result<Library, String> {
        unsafe { Library::new(name).map_err(|e| format!("{}", e)) }
    };

    let lib_candidates: Vec<String> = {
        let mut candidates = Vec::new();
        #[cfg(windows)]
        {
            const MAX_PATH: u32 = 260;
            let mut buf = [0u8; MAX_PATH as usize];
            unsafe {
                let module = GetModuleHandleA(b"mediampv.dll\0".as_ptr() as *const i8);
                if !module.is_null() {
                    let len = GetModuleFileNameA(module, buf.as_mut_ptr() as *mut i8, MAX_PATH);
                    if len > 0 {
                        let path = String::from_utf8_lossy(&buf[..len as usize]).to_string();
                        if let Some(parent) = std::path::Path::new(&path).parent() {
                            let dir = parent.to_string_lossy().to_string();
                            for name in &["libmpv-2.dll", "mpv-2.dll", "mpv.dll"] {
                                candidates.push(format!("{}/{}", dir, name));
                            }
                            // Also try the libmpv prebuilt directory (sibling of build-ci)
                            let mediamp_dir = parent.parent();
                            if let Some(top) = mediamp_dir {
                                let top = top.to_string_lossy().to_string();
                                let lib_dir = format!("{}/libmpv/lib/windows/x86_64/", top);
                                for name in &["libmpv-2.dll", "mpv-2.dll", "mpv.dll"] {
                                    candidates.push(format!("{}{}", lib_dir, name));
                                }
                            }
                        }
                    }
                }
            }
            candidates.push("libmpv-2.dll".to_string());
            candidates.push("mpv-2.dll".to_string());
            candidates.push("mpv.dll".to_string());
        }
        #[cfg(not(windows))]
        {
            candidates.push("libmpv.so.2".to_string());
            candidates.push("libmpv.so.1".to_string());
            candidates.push("libmpv.so".to_string());
            // Common system paths on Linux (not on Windows)
            let lib_dirs = [
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/usr/lib/i386-linux-gnu",
                "/usr/lib",
                "/usr/lib64",
                "/usr/local/lib",
                "/lib/x86_64-linux-gnu",
                "/lib",
            ];
            let sonames = ["libmpv.so.2", "libmpv.so.1", "libmpv.so"];
            for dir in &lib_dirs {
                for name in &sonames {
                    candidates.push(format!("{}/{}", dir, name));
                }
            }
        }
        candidates
    };

    let mut last_err = String::new();
    for name in &lib_candidates {
        match try_load(name) {
            Ok(lib) => {
                eprintln!("[mpv-rust] Loaded libmpv from '{}'", name);
                let lib = Box::leak(Box::new(lib));
                return unsafe { Ok(MpvFunctions {
                    mpv_create: load_fn(lib, "mpv_create"),
                    mpv_initialize: load_fn(lib, "mpv_initialize"),
                    mpv_destroy: load_fn(lib, "mpv_destroy"),
                    mpv_terminate_destroy: load_fn(lib, "mpv_terminate_destroy"),
                    mpv_command: load_fn(lib, "mpv_command"),
                    mpv_set_option_string: load_fn(lib, "mpv_set_option_string"),
                    mpv_get_property: load_fn(lib, "mpv_get_property"),
                    mpv_set_property: load_fn(lib, "mpv_set_property"),
                    mpv_get_property_string: load_fn(lib, "mpv_get_property_string"),
                    mpv_free: load_fn(lib, "mpv_free"),
                    mpv_observe_property: load_fn(lib, "mpv_observe_property"),
                    mpv_unobserve_property: load_fn(lib, "mpv_unobserve_property"),
                    mpv_error_string: load_fn(lib, "mpv_error_string"),
                    mpv_wait_event: load_fn(lib, "mpv_wait_event"),
                    mpv_wakeup: load_fn(lib, "mpv_wakeup"),
                    mpv_set_wakeup_callback: load_fn(lib, "mpv_set_wakeup_callback"),
                    mpv_render_context_create: load_fn(lib, "mpv_render_context_create"),
                    mpv_render_context_free: load_fn(lib, "mpv_render_context_free"),
                    mpv_render_context_update: {
                        let sym: Option<libloading::Symbol<unsafe extern "C" fn(*mut c_void) -> u64>> = lib.get(b"mpv_render_context_update\0").ok();
                        sym.map(|s| *s)
                    },
                    mpv_render_context_render: load_fn(lib, "mpv_render_context_render"),
                    mpv_render_context_set_update_callback: load_fn(lib, "mpv_render_context_set_update_callback"),
                }) };
            }
            Err(e) => {
                eprintln!("[mpv-rust] Failed to load '{}': {}", name, e);
                last_err = format!("{}: {}", name, e);
            }
        }
    }
    Err(format!("Failed to load libmpv: {}", last_err))
}

pub fn load_mpv() -> Result<&'static MpvFunctions, String> {
    let result = MPV_RESULT.get_or_init(|| {
        match load_mpv_inner() {
            Ok(fns) => Ok(Box::leak(Box::new(fns))),
            Err(e) => Err(e),
        }
    });
    match result {
        Ok(fns) => Ok(fns),
        Err(e) => Err(e.clone()),
    }
}
