use std::ffi::{c_void, CStr, CString};
use std::os::raw::c_char;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use jni::objects::GlobalRef;
use jni::sys::jboolean;
use jni::JNIEnv;


use crate::ffi::*;
use crate::renderer::{GLLoader, OpenGL};

unsafe extern "C" fn mpv_render_update_callback(ctx: *mut c_void) {
    if !ctx.is_null() {
        let fns = crate::ffi::load_mpv();
        if let Ok(fns) = fns {
            (fns.mpv_wakeup)(ctx);
        }
    }
}

unsafe extern "C" fn get_proc_address_mpv(
    _ctx: *mut c_void,
    name: *const c_char,
) -> *mut c_void {
    if name.is_null() {
        return std::ptr::null_mut();
    }
    let name_str = unsafe { CStr::from_ptr(name) };
    let name_str = name_str.to_str().unwrap_or("");
    let loader = GLLoader::new();
    let addr = loader.get_proc(name_str).unwrap_or(std::ptr::null_mut());
    if addr.is_null() {
        eprintln!("[mpv-rust] get_proc_address FAILED: {}", name_str);
    }
    addr
}

pub struct MpvPlayer {
    fns: &'static MpvFunctions,
    ctx: AtomicPtr<c_void>,
    render_ctx: AtomicPtr<c_void>,
    gl: Mutex<OpenGL>,
    event_thread_stop: Arc<AtomicBool>,
    event_thread_handle: Mutex<Option<JoinHandle<()>>>,
    event_listener: Mutex<Option<GlobalRef>>,
    jvm: Mutex<Option<jni::JavaVM>>,
    gl_device: AtomicPtr<c_void>,
    gl_context: AtomicPtr<c_void>,
    gl_drawable: AtomicPtr<c_void>,
}

impl MpvPlayer {
    pub fn new() -> Option<Arc<Self>> {
        // Set LC_NUMERIC to "C" before any mpv function calls.
        // mpv internally calls setlocale(LC_NUMERIC, "C") during mpv_create,
        // but that is unsafe in a multi-threaded JVM. We do it once here
        // (still process-global, but only during module init) and tell mpv
        // to skip its own setlocale via MPV_NOLOCALE.
        std::env::set_var("MPV_NOLOCALE", "1");
        unsafe {
            libc::setlocale(libc::LC_NUMERIC, b"C\0".as_ptr() as *const libc::c_char);
        }

        let fns = match load_mpv() {
            Ok(f) => f,
            Err(e) => {
                log::error!("{}", e);
                return None;
            }
        };

        let ctx = unsafe { (fns.mpv_create)() };

        if ctx.is_null() {
            eprintln!("[mpv-rust] mpv_create failed");
            return None;
        }

        Some(Arc::new(MpvPlayer {
            fns,
            ctx: AtomicPtr::new(ctx),
            render_ctx: AtomicPtr::new(std::ptr::null_mut()),
            gl: Mutex::new(OpenGL::new()),
            event_thread_stop: Arc::new(AtomicBool::new(false)),
            event_thread_handle: Mutex::new(None),
            event_listener: Mutex::new(None),
            jvm: Mutex::new(None),
            gl_device: AtomicPtr::new(std::ptr::null_mut()),
            gl_context: AtomicPtr::new(std::ptr::null_mut()),
            gl_drawable: AtomicPtr::new(std::ptr::null_mut()),
        }))
    }

    pub fn ctx_ptr(&self) -> *mut c_void {
        self.ctx.load(Ordering::Acquire)
    }

    pub fn initialize(&self) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        unsafe { (self.fns.mpv_initialize)(ctx) == 0 }
    }

    pub fn start_event_loop(self: &Arc<Self>) {
        let stop = self.event_thread_stop.clone();
        let this = self.clone();

        let handle = std::thread::spawn(move || {
            let ctx = this.ctx_ptr();
            if ctx.is_null() {
                return;
            }

            loop {
                if stop.load(Ordering::Acquire) {
                    break;
                }

                let event = unsafe { (this.fns.mpv_wait_event)(ctx, 0.5) };
                if event.is_null() {
                    continue;
                }
                let ev = unsafe { &*event };

                match ev.event_id {
                    mpv_event_id::MPV_EVENT_NONE => continue,
                    mpv_event_id::MPV_EVENT_SHUTDOWN => break,
                    mpv_event_id::MPV_EVENT_PROPERTY_CHANGE => {
                        this.dispatch_property_change(ev);
                    }
                    _ => {}
                }
            }
        });

        *self.event_thread_handle.lock().unwrap() = Some(handle);
    }

    fn dispatch_property_change(&self, event: &mpv_event) {
        if event.data.is_null() {
            return;
        }
        let prop = unsafe { &*(event.data as *const mpv_event_property) };
        if prop.name.is_null() {
            return;
        }
        let name = unsafe { CStr::from_ptr(prop.name) }
            .to_str()
            .unwrap_or("")
            .to_string();

        let listener_guard = self.event_listener.lock().unwrap();
        let jvm_guard = self.jvm.lock().unwrap();

        if let (Some(listener), Some(jvm)) = (listener_guard.as_ref(), jvm_guard.as_ref()) {
            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => return,
            };

            let j_name = match env.new_string(&name) {
                Ok(s) => s,
                Err(_) => return,
            };

            match prop.format {
                mpv_format::MPV_FORMAT_NONE => {
                    let _ = env.call_method(
                        listener.as_obj(),
                        "onPropertyChange",
                        "(Ljava/lang/String;)V",
                        &[jni::objects::JValue::Object(&j_name)],
                    );
                }
                mpv_format::MPV_FORMAT_FLAG => {
                    let val = unsafe { *(prop.data as *const i32) } != 0;
                    let _ = env.call_method(
                        listener.as_obj(),
                        "onPropertyChange",
                        "(Ljava/lang/String;Z)V",
                        &[
                            jni::objects::JValue::Object(&j_name),
                            jni::objects::JValue::Bool(val as jboolean),
                        ],
                    );
                }
                mpv_format::MPV_FORMAT_INT64 => {
                    let val = unsafe { *(prop.data as *const i64) };
                    let _ = env.call_method(
                        listener.as_obj(),
                        "onPropertyChange",
                        "(Ljava/lang/String;J)V",
                        &[
                            jni::objects::JValue::Object(&j_name),
                            jni::objects::JValue::Long(val),
                        ],
                    );
                }
                mpv_format::MPV_FORMAT_DOUBLE => {
                    let val = unsafe { *(prop.data as *const f64) };
                    let _ = env.call_method(
                        listener.as_obj(),
                        "onPropertyChange",
                        "(Ljava/lang/String;D)V",
                        &[
                            jni::objects::JValue::Object(&j_name),
                            jni::objects::JValue::Double(val),
                        ],
                    );
                }
                mpv_format::MPV_FORMAT_STRING => {
                    let ptr = unsafe { *(prop.data as *const *const c_char) };
                    let val = if ptr.is_null() {
                        String::new()
                    } else {
                        unsafe { CStr::from_ptr(ptr) }
                            .to_string_lossy()
                            .into_owned()
                    };
                    let j_val = match env.new_string(&val) {
                        Ok(s) => s,
                        Err(_) => return,
                    };
                    let _ = env.call_method(
                        listener.as_obj(),
                        "onPropertyChange",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        &[
                            jni::objects::JValue::Object(&j_name),
                            jni::objects::JValue::Object(&j_val),
                        ],
                    );
                }
                _ => {}
            }
        }
    }

    pub fn command(&self, args: &[&str]) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }

        let c_strings: Vec<CString> = args
            .iter()
            .map(|s| CString::new(*s).expect("null in command arg"))
            .collect();
        let mut raw_ptrs: Vec<*const c_char> =
            c_strings.iter().map(|cs| cs.as_ptr()).collect();
        raw_ptrs.push(std::ptr::null());

        unsafe { (self.fns.mpv_command)(ctx, raw_ptrs.as_ptr()) == 0 }
    }

    pub fn set_option(&self, key: &str, value: &str) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            eprintln!("[mpv-rust] set_option: ctx is null");
            return false;
        }
        let c_key = CString::new(key).unwrap();
        let c_val = CString::new(value).unwrap();
        unsafe { (self.fns.mpv_set_option_string)(ctx, c_key.as_ptr(), c_val.as_ptr()) >= 0 }
    }

    pub fn get_property_int(&self, name: &str) -> i32 {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return 0;
        }
        let c_name = CString::new(name).unwrap();
        let mut result: i64 = 0;
        unsafe {
            (self.fns.mpv_get_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_INT64,
                &mut result as *mut _ as *mut c_void,
            );
        }
        result as i32
    }

    pub fn get_property_bool(&self, name: &str) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        let mut result: i32 = 0;
        unsafe {
            (self.fns.mpv_get_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_FLAG,
                &mut result as *mut _ as *mut c_void,
            );
        }
        result != 0
    }

    pub fn get_property_double(&self, name: &str) -> f64 {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return 0.0;
        }
        let c_name = CString::new(name).unwrap();
        let mut result: f64 = 0.0;
        unsafe {
            (self.fns.mpv_get_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_DOUBLE,
                &mut result as *mut _ as *mut c_void,
            );
        }
        result
    }

    pub fn get_property_string(&self, name: &str) -> String {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return String::new();
        }
        let c_name = CString::new(name).unwrap();
        unsafe {
            let ptr = (self.fns.mpv_get_property_string)(ctx, c_name.as_ptr());
            if ptr.is_null() {
                return String::new();
            }
            let result = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            (self.fns.mpv_free)(ptr as *mut c_void);
            result
        }
    }

    pub fn set_property_int(&self, name: &str, value: i32) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        let mut v: i64 = value as i64;
        unsafe {
            (self.fns.mpv_set_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_INT64,
                &mut v as *mut _ as *mut c_void,
            ) == 0
        }
    }

    pub fn set_property_bool(&self, name: &str, value: bool) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        let mut v: i32 = if value { 1 } else { 0 };
        unsafe {
            (self.fns.mpv_set_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_FLAG,
                &mut v as *mut _ as *mut c_void,
            ) == 0
        }
    }

    pub fn set_property_double(&self, name: &str, value: f64) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        unsafe {
            (self.fns.mpv_set_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_DOUBLE,
                &value as *const _ as *const c_void,
            ) == 0
        }
    }

    pub fn set_property_string(&self, name: &str, value: &str) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        let c_val = CString::new(value).unwrap();
        let c_val_ptr = c_val.as_ptr();
        unsafe {
            (self.fns.mpv_set_property)(
                ctx,
                c_name.as_ptr(),
                mpv_format::MPV_FORMAT_STRING,
                &c_val_ptr as *const *const c_char as *const c_void,
            ) == 0
        }
    }

    pub fn observe_property(&self, name: &str, format: i32, reply_data: i64) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        let c_name = CString::new(name).unwrap();
        let fmt = match format {
            0 => mpv_format::MPV_FORMAT_NONE,
            1 => mpv_format::MPV_FORMAT_STRING,
            2 => mpv_format::MPV_FORMAT_OSD_STRING,
            3 => mpv_format::MPV_FORMAT_FLAG,
            4 => mpv_format::MPV_FORMAT_INT64,
            5 => mpv_format::MPV_FORMAT_DOUBLE,
            _ => mpv_format::MPV_FORMAT_NONE,
        };
        unsafe { (self.fns.mpv_observe_property)(ctx, reply_data as u64, c_name.as_ptr(), fmt) == 0 }
    }

    pub fn unobserve_property(&self, reply_data: i64) -> bool {
        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }
        unsafe { (self.fns.mpv_unobserve_property)(ctx, reply_data as u64) == 0 }
    }

    // --- Render context & texture ---

    /// Returns Ok(result) if GL context was successfully made current,
    /// Err(()) if wglMakeCurrent failed.
    unsafe fn with_gl_context<T, F: FnOnce() -> T>(&self, f: F) -> Result<T, ()> {
        #[cfg(windows)]
        {
            let device = self.gl_device.load(Ordering::Acquire);
            let context = self.gl_context.load(Ordering::Acquire);
            if !device.is_null() && !context.is_null() {
                #[link(name = "opengl32")]
                extern "system" {
                    fn wglGetCurrentDC() -> *mut c_void;
                    fn wglGetCurrentContext() -> *mut c_void;
                    fn wglMakeCurrent(hdc: *mut c_void, hglrc: *mut c_void) -> i32;
                }
                let old_dc = wglGetCurrentDC();
                let old_ctx = wglGetCurrentContext();
                if wglMakeCurrent(device, context) == 0 {
                    eprintln!("[mpv-rust] wglMakeCurrent FAILED");
                    wglMakeCurrent(old_dc, old_ctx);
                    return Err(());
                }
                let result = f();
                wglMakeCurrent(old_dc, old_ctx);
                return Ok(result);
            }
        }
        Ok(f())
    }

    pub fn create_render_context(
        &self,
        device_ptr: u64,
        context_ptr: u64,
        drawable_ptr: u64,
    ) -> bool {
        self.gl_device.store(device_ptr as *mut c_void, Ordering::Release);
        self.gl_context.store(context_ptr as *mut c_void, Ordering::Release);
        self.gl_drawable.store(drawable_ptr as *mut c_void, Ordering::Release);

        let ctx = self.ctx_ptr();
        if ctx.is_null() {
            return false;
        }

        unsafe {
            let ok = self.with_gl_context(|| {
                let gl_init_params = mpv_opengl_init_params {
                    get_proc_address: Some(get_proc_address_mpv),
                    get_proc_address_ctx: std::ptr::null_mut(),
                };

                let mut render_ctx: *mut c_void = std::ptr::null_mut();
                let params = [
                    mpv_render_param {
                        type_: MPV_RENDER_PARAM_API_TYPE,
                        data: b"opengl\0".as_ptr() as *mut c_void,
                    },
                    mpv_render_param {
                        type_: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS,
                        data: &gl_init_params as *const _ as *mut c_void,
                    },
                    mpv_render_param {
                        type_: MPV_RENDER_PARAM_INVALID,
                        data: std::ptr::null_mut(),
                    },
                ];

                let result = (self.fns.mpv_render_context_create)(&mut render_ctx, ctx, params.as_ptr());
                if result != 0 {
                    eprintln!("[mpv-rust] mpv_render_context_create returned {}", result);
                    return false;
                }
                self.render_ctx.store(render_ctx, Ordering::Release);
                (self.fns.mpv_render_context_set_update_callback)(
                    render_ctx,
                    Some(mpv_render_update_callback),
                    ctx,
                );
                true
            });
            match ok {
                Ok(true) => !self.render_ctx.load(Ordering::Acquire).is_null(),
                _ => false,
            }
        }
    }

    pub fn destroy_render_context(&self) -> bool {
        let render_ctx = self.render_ctx.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !render_ctx.is_null() {
            unsafe {
                let _ = self.with_gl_context(|| {
                    (self.fns.mpv_render_context_free)(render_ctx);
                });
            }
        }
        true
    }

    pub fn create_texture(&self, width: i32, height: i32) -> i32 {
        let loader = GLLoader::new();
        let mut gl = self.gl.lock().unwrap();
        unsafe {
            match self.with_gl_context(|| gl.create_texture(width as u32, height as u32, &loader)) {
                Ok(Some(id)) => id as i32,
                _ => 0,
            }
        }
    }

    pub fn release_texture(&self) -> bool {
        let loader = GLLoader::new();
        let mut gl = self.gl.lock().unwrap();
        unsafe {
            let _ = self.with_gl_context(|| gl.release_texture(&loader));
        }
        true
    }

    pub fn render_frame(&self) -> bool {
        let render_ctx = self.render_ctx.load(Ordering::Acquire);
        if render_ctx.is_null() {
            return false;
        }
        let loader = GLLoader::new();
        let mut gl = self.gl.lock().unwrap();
        unsafe {
            match self.with_gl_context(|| gl.render_frame(render_ctx, &loader)) {
                Ok(result) => result,
                Err(_) => false,
            }
        }
    }

    pub fn debug_render_solid(&self, r: f32, g: f32, b: f32, a: f32) -> bool {
        let loader = GLLoader::new();
        let mut gl = self.gl.lock().unwrap();
        unsafe {
            match self.with_gl_context(|| gl.debug_render_solid(r, g, b, a, &loader)) {
                Ok(result) => result,
                Err(_) => false,
            }
        }
    }

    pub fn read_texture_stats(&self) -> String {
        let gl = self.gl.lock().unwrap();
        format!(
            "fbo={}, texture={}, size={}x{}",
            gl.fbo, gl.texture, gl.width, gl.height
        )
    }

    // --- Event Listener ---

    pub fn set_event_listener(&self, env: &mut JNIEnv, listener: &jni::objects::JObject) -> bool {
        let global_ref = match env.new_global_ref(listener) {
            Ok(r) => r,
            Err(e) => {
                log::warn!("Failed to create global ref for EventListener: {}", e);
                return false;
            }
        };

        let jvm = match env.get_java_vm() {
            Ok(vm) => vm,
            Err(e) => {
                log::warn!("Failed to get JavaVM: {}", e);
                return false;
            }
        };

        *self.jvm.lock().unwrap() = Some(jvm);
        *self.event_listener.lock().unwrap() = Some(global_ref);
        true
    }

    // --- Lifecycle ---

    pub fn destroy(&self) -> bool {
        self.event_thread_stop.store(true, Ordering::Release);

        let ctx = self.ctx_ptr();
        if !ctx.is_null() {
            unsafe { (self.fns.mpv_wakeup)(ctx); }
        }

        if let Ok(mut handle) = self.event_thread_handle.lock() {
            if let Some(h) = handle.take() {
                let _ = h.join();
            }
        }

        self.destroy_render_context();
        self.release_texture();

        let ctx = self.ctx.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !ctx.is_null() {
            unsafe { (self.fns.mpv_terminate_destroy)(ctx); }
        }
        true
    }
}

impl Drop for MpvPlayer {
    fn drop(&mut self) {
        self.event_thread_stop.store(true, Ordering::Release);
        let ctx = self.ctx_ptr();
        if !ctx.is_null() {
            unsafe { (self.fns.mpv_wakeup)(ctx); }
        }
        if let Ok(mut handle) = self.event_thread_handle.lock() {
            if let Some(h) = handle.take() {
                let _ = h.join();
            }
        }
        self.destroy_render_context();
        self.release_texture();
        let ctx = self.ctx.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !ctx.is_null() {
            unsafe { (self.fns.mpv_terminate_destroy)(ctx); }
        }
    }
}
