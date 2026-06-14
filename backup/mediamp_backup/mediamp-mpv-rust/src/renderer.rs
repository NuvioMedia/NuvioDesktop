use std::ffi::c_void;
use std::os::raw::c_int;

use log::info;

use crate::ffi::*;

pub struct OpenGL {
    pub fbo: u32,
    pub texture: u32,
    pub width: u32,
    pub height: u32,
}

impl OpenGL {
    pub fn new() -> Self {
        OpenGL { fbo: 0, texture: 0, width: 0, height: 0 }
    }

    pub fn create_texture(&mut self, width: u32, height: u32, loader: &GLLoader) -> Option<u32> {
        self.release_texture(loader);

        unsafe {
            let gl_gen_textures: Option<unsafe extern "C" fn(i32, *mut u32)> =
                loader.get_proc("glGenTextures").map(|p| std::mem::transmute(p));
            let gl_bind_tex: Option<unsafe extern "C" fn(u32, u32)> =
                loader.get_proc("glBindTexture").map(|p| std::mem::transmute(p));
            let gl_tex_image: Option<unsafe extern "C" fn(u32, i32, i32, i32, i32, i32, u32, u32, *const c_void)> =
                loader.get_proc("glTexImage2D").map(|p| std::mem::transmute(p));
            let gl_gen_fbos: Option<unsafe extern "C" fn(i32, *mut u32)> =
                loader.get_proc("glGenFramebuffers").map(|p| std::mem::transmute(p));
            let gl_bind_fbo: Option<unsafe extern "C" fn(u32, u32)> =
                loader.get_proc("glBindFramebuffer").map(|p| std::mem::transmute(p));
            let gl_fbo_tex: Option<unsafe extern "C" fn(u32, u32, u32, u32, i32)> =
                loader.get_proc("glFramebufferTexture2D").map(|p| std::mem::transmute(p));

            let gl_gen_textures = gl_gen_textures?;
            let gl_bind_tex = gl_bind_tex?;
            let gl_tex_image = gl_tex_image?;
            let gl_gen_fbos = gl_gen_fbos?;
            let gl_bind_fbo = gl_bind_fbo?;
            let gl_fbo_tex = gl_fbo_tex?;

            let mut new_tex: u32 = 0;
            gl_gen_textures(1, &mut new_tex);
            gl_bind_tex(GL_TEXTURE_2D as u32, new_tex);
            gl_tex_image(
                GL_TEXTURE_2D as u32, 0, GL_RGBA8,
                width as i32, height as i32, 0,
                GL_RGBA as u32, GL_UNSIGNED_BYTE as u32,
                std::ptr::null(),
            );

            let mut new_fbo: u32 = 0;
            gl_gen_fbos(1, &mut new_fbo);
            gl_bind_fbo(GL_FRAMEBUFFER as u32, new_fbo);
            gl_fbo_tex(
                GL_FRAMEBUFFER as u32, GL_COLOR_ATTACHMENT0 as u32,
                GL_TEXTURE_2D as u32, new_tex, 0,
            );

            self.fbo = new_fbo;
            self.texture = new_tex;
            self.width = width;
            self.height = height;

            info!("Created GL texture {} FBO {} ({}x{})", new_tex, new_fbo, width, height);
            Some(new_tex)
        }
    }

    pub fn release_texture(&mut self, loader: &GLLoader) {
        if self.fbo != 0 {
            unsafe {
                if let Some(proc) = loader.get_proc("glDeleteFramebuffers") {
                    let del: unsafe extern "C" fn(i32, *const u32) = std::mem::transmute(proc);
                    del(1, &self.fbo);
                }
            }
            self.fbo = 0;
        }
        if self.texture != 0 {
            unsafe {
                if let Some(proc) = loader.get_proc("glDeleteTextures") {
                    let del: unsafe extern "C" fn(i32, *const u32) = std::mem::transmute(proc);
                    del(1, &self.texture);
                }
            }
            self.texture = 0;
        }
        self.width = 0;
        self.height = 0;
    }

    pub fn render_frame(&mut self, render_ctx: *mut c_void, loader: &GLLoader) -> bool {
        if render_ctx.is_null() || self.fbo == 0 {
            return false;
        }

        unsafe {
            let gl_bind_fbo = match loader.get_proc("glBindFramebuffer") {
                Some(p) => { let f: unsafe extern "C" fn(u32, u32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_viewport = match loader.get_proc("glViewport") {
                Some(p) => { let f: unsafe extern "C" fn(i32, i32, i32, i32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_clear_color = match loader.get_proc("glClearColor") {
                Some(p) => { let f: unsafe extern "C" fn(f32, f32, f32, f32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_clear = match loader.get_proc("glClear") {
                Some(p) => { let f: unsafe extern "C" fn(u32) = std::mem::transmute(p); f }
                None => return false,
            };

            gl_bind_fbo(GL_FRAMEBUFFER as u32, self.fbo);
            gl_viewport(0, 0, self.width as i32, self.height as i32);
            gl_clear_color(0.0, 0.0, 0.0, 1.0);
            gl_clear(GL_COLOR_BUFFER_BIT as u32);

            let fbo_params = mpv_opengl_fbo {
                fbo: self.fbo as c_int,
                w: self.width as c_int,
                h: self.height as c_int,
                internal_format: GL_RGBA8,
            };

            let params = [
                mpv_render_param { type_: MPV_RENDER_PARAM_OPENGL_FBO, data: &fbo_params as *const _ as *mut c_void },
                mpv_render_param { type_: MPV_RENDER_PARAM_INVALID, data: std::ptr::null_mut() },
            ];

            let mpv = match crate::ffi::load_mpv() {
                Ok(m) => m,
                Err(_) => return false,
            };
            let needs_render = mpv.mpv_render_context_update.map_or(true, |update| update(render_ctx) != 0);
            if needs_render {
                let result = (mpv.mpv_render_context_render)(render_ctx, params.as_ptr());
                gl_bind_fbo(GL_FRAMEBUFFER as u32, 0);
                result == 0
            } else {
                gl_bind_fbo(GL_FRAMEBUFFER as u32, 0);
                true
            }
        }
    }

    pub fn debug_render_solid(&mut self, r: f32, g: f32, b: f32, a: f32, loader: &GLLoader) -> bool {
        if self.fbo == 0 {
            return false;
        }
        unsafe {
            let gl_bind_fbo = match loader.get_proc("glBindFramebuffer") {
                Some(p) => { let f: unsafe extern "C" fn(u32, u32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_viewport = match loader.get_proc("glViewport") {
                Some(p) => { let f: unsafe extern "C" fn(i32, i32, i32, i32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_clear_color = match loader.get_proc("glClearColor") {
                Some(p) => { let f: unsafe extern "C" fn(f32, f32, f32, f32) = std::mem::transmute(p); f }
                None => return false,
            };
            let gl_clear = match loader.get_proc("glClear") {
                Some(p) => { let f: unsafe extern "C" fn(u32) = std::mem::transmute(p); f }
                None => return false,
            };

            gl_bind_fbo(GL_FRAMEBUFFER as u32, self.fbo);
            gl_viewport(0, 0, self.width as i32, self.height as i32);
            gl_clear_color(r, g, b, a);
            gl_clear(GL_COLOR_BUFFER_BIT as u32);
            gl_bind_fbo(GL_FRAMEBUFFER as u32, 0);
        }
        true
    }
}

pub struct GLLoader;

impl GLLoader {
    pub fn new() -> Self { GLLoader }

    pub fn get_proc(&self, name: &str) -> Option<*mut c_void> {
        let c_name = std::ffi::CString::new(name).ok()?;

        #[cfg(target_os = "windows")]
        {
            unsafe {
                let module = GetModuleHandleA(b"opengl32.dll\0".as_ptr() as *const i8);
                if module.is_null() {
                    return None;
                }
                let wgl_name = b"wglGetProcAddress\0".as_ptr() as *const i8;
                let proc_addr = GetProcAddress(module, wgl_name);
                if !proc_addr.is_null() {
                    let wgl: unsafe extern "system" fn(*const u8) -> *mut c_void = std::mem::transmute(proc_addr);
                    let addr = wgl(c_name.as_ptr() as *const u8);
                    if !addr.is_null() {
                        return Some(addr);
                    }
                }
                let addr = GetProcAddress(module, c_name.as_ptr());
                if !addr.is_null() {
                    return Some(addr);
                }
                None
            }
        }

        #[cfg(unix)]
        {
            unsafe {
                let addr = libc::dlsym(libc::RTLD_DEFAULT, c_name.as_ptr());
                if !addr.is_null() {
                    return Some(addr);
                }
                if let Some(addr) = try_egl_get_proc(c_name.as_ptr()) {
                    return Some(addr);
                }
                if let Some(addr) = try_glx_get_proc(c_name.as_ptr()) {
                    return Some(addr);
                }
                None
            }
        }

        #[cfg(not(any(unix, target_os = "windows")))]
        None
    }
}

#[cfg(target_os = "windows")]
extern "system" {
    fn GetModuleHandleA(name: *const i8) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, name: *const i8) -> *mut c_void;
}

#[cfg(unix)]
unsafe fn try_egl_get_proc(name: *const c_char) -> Option<*mut c_void> {
    type EglGetProc = unsafe extern "C" fn(*const c_char) -> *mut c_void;
    let lib = libc::dlopen(b"libEGL.so\0".as_ptr() as *const i8, libc::RTLD_LAZY | libc::RTLD_LOCAL);
    if lib.is_null() { return None; }
    let sym = libc::dlsym(lib, b"eglGetProcAddress\0".as_ptr() as *const i8);
    if sym.is_null() { libc::dlclose(lib); return None; }
    let get_proc: EglGetProc = std::mem::transmute(sym);
    let addr = get_proc(name);
    libc::dlclose(lib);
    if addr.is_null() { None } else { Some(addr) }
}

#[cfg(unix)]
unsafe fn try_glx_get_proc(name: *const c_char) -> Option<*mut c_void> {
    type GlXGetProc = unsafe extern "C" fn(*const *const u8) -> *mut c_void;
    let lib = libc::dlopen(b"libGL.so\0".as_ptr() as *const i8, libc::RTLD_LAZY | libc::RTLD_LOCAL);
    if lib.is_null() { return None; }
    let sym = libc::dlsym(lib, b"glXGetProcAddress\0".as_ptr() as *const i8);
    if sym.is_null() { libc::dlclose(lib); return None; }
    let get_proc: GlXGetProc = std::mem::transmute(sym);
    let addr = get_proc(&(name.cast::<u8>()));
    libc::dlclose(lib);
    if addr.is_null() { None } else { Some(addr) }
}
