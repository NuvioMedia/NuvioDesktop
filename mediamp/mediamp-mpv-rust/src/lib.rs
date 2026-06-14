#![allow(dead_code)]

mod ffi;
mod player;
mod renderer;

use std::sync::Arc;

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use log::info;

use player::MpvPlayer;

fn get_player<'a>(ptr: jlong) -> &'a Arc<MpvPlayer> {
    unsafe { &*(ptr as *const Arc<MpvPlayer>) }
}

// ============================================================================
// MPVHandleKt — common functions (org.openani.mediamp.mpv.MPVHandleKt)
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nGlobalInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let _ = env_logger::try_init();
    jboolean::from(true)
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nMake(
    _env: JNIEnv,
    _class: JClass,
    _app_context: JObject,
) -> jlong {
    match MpvPlayer::new() {
        Some(player) => {
            Box::into_raw(Box::new(player)) as jlong
        }
        None => {
            log::warn!("Failed to create MpvPlayer");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nInitialize(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    let player = get_player(ptr);
    let ok = player.initialize();
    if ok {
        player.start_event_loop();
    }
    jboolean::from(ok)
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nSetEventListener(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    listener: JObject,
) -> jboolean {
    jboolean::from(get_player(ptr).set_event_listener(&mut env, &listener))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nCommand(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    args: JObjectArray,
) -> jboolean {
    let args_array = match env.get_array_length(&args) {
        Ok(len) => len,
        Err(_) => return jboolean::from(false),
    };

    let mut cmd_args: Vec<String> = Vec::with_capacity(args_array as usize);
    for i in 0..args_array {
        let elem = match env.get_object_array_element(&args, i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let jstr = JString::from(elem);
        let s: String = match env.get_string(&jstr) {
            Ok(s) => s.into(),
            Err(_) => continue,
        };
        cmd_args.push(s);
    }

    let str_refs: Vec<&str> = cmd_args.iter().map(|s| s.as_str()).collect();
    eprintln!("[mpv-rust-debug] nCommand args={:?}", cmd_args);
    jboolean::from(get_player(ptr).command(&str_refs))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nOption(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: JString,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    let value: String = match env.get_string(&value) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).set_option(&key, &value))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nGetPropertyInt(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
) -> jint {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    get_player(ptr).get_property_int(&key)
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nGetPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).get_property_bool(&key))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nGetPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
) -> jdouble {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return 0.0,
    };
    get_player(ptr).get_property_double(&key)
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nGetPropertyString<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ptr: jlong,
    key: JString<'local>,
) -> jstring {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let value = get_player(ptr).get_property_string(&key);
    match env.new_string(&value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nSetPropertyInt(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: jint,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).set_property_int(&key, value))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nSetPropertyBoolean(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: jboolean,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).set_property_bool(&key, value != 0))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nSetPropertyDouble(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: jdouble,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).set_property_double(&key, value))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nSetPropertyString(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    value: JString,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    let value: String = match env.get_string(&value) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).set_property_string(&key, &value))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nObserveProperty(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    key: JString,
    format: jint,
    reply_data: jlong,
) -> jboolean {
    let key: String = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => return jboolean::from(false),
    };
    jboolean::from(get_player(ptr).observe_property(&key, format, reply_data))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nUnobserveProperty(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    reply_data: jlong,
) -> jboolean {
    jboolean::from(get_player(ptr).unobserve_property(reply_data))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    jboolean::from(get_player(ptr).destroy())
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleKt_nFinalize(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        info!("Finalizing MpvPlayer, ptr={:x}", ptr);
        unsafe {
            drop(Box::from_raw(ptr as *mut Arc<MpvPlayer>));
        }
    }
}

// ============================================================================
// MPVHandleDesktop — desktop render functions (@file:JvmName("MPVHandleDesktop"))
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nCreateRenderContext(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    device_ptr: jlong,
    context_ptr: jlong,
    drawable_ptr: jlong,
) -> jboolean {
    let player = get_player(ptr);
    jboolean::from(player.create_render_context(
        device_ptr as u64,
        context_ptr as u64,
        drawable_ptr as u64,
    ))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nDestroyRenderContext(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    jboolean::from(get_player(ptr).destroy_render_context())
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nCreateTexture(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    width: jint,
    height: jint,
) -> jint {
    get_player(ptr).create_texture(width, height)
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nReleaseTexture(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    jboolean::from(get_player(ptr).release_texture())
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nRenderFrameToTexture(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    jboolean::from(get_player(ptr).render_frame())
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nDebugRenderSolid(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    red: jfloat,
    green: jfloat,
    blue: jfloat,
    alpha: jfloat,
) -> jboolean {
    jboolean::from(get_player(ptr).debug_render_solid(red, green, blue, alpha))
}

#[no_mangle]
pub extern "system" fn Java_org_openani_mediamp_mpv_MPVHandleDesktop_nReadTextureStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    ptr: jlong,
) -> jstring {
    let stats = get_player(ptr).read_texture_stats();
    match env.new_string(&stats) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
