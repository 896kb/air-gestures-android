//! JNI entry points for `dev.airgestures.Bridge` (static natives). A connection
//! is a boxed `Connection` behind a `long` handle; errors become IOException.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jdouble, jint, jlong, jstring};

use crate::Connection;

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/io/IOException", message);
}

/// # Safety
/// `handle` must come from `open` and not have been passed to `close`.
unsafe fn connection<'a>(handle: jlong) -> &'a mut Connection {
    unsafe { &mut *(handle as *mut Connection) }
}

fn bytes_out(env: &mut JNIEnv, result: Result<Vec<u8>, String>) -> jbyteArray {
    match result.and_then(|bytes| env.byte_array_from_slice(&bytes).map_err(|e| e.to_string())) {
        Ok(array) => array.into_raw(),
        Err(message) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    owner_key: JByteArray<'local>,
    scheme_guess: jint,
) -> jlong {
    let opened = env
        .convert_byte_array(&owner_key)
        .map_err(|e| e.to_string())
        .and_then(|key| Connection::new(&key, scheme_guess.max(0) as usize));
    match opened {
        Ok(connection) => Box::into_raw(Box::new(connection)) as jlong,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_request<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.request();
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_feed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bytes: JByteArray<'local>,
    now: jdouble,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&bytes)
        .map_err(|e| e.to_string())
        .and_then(|bytes| unsafe { connection(handle) }.feed(&bytes, now));
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.tick(now);
    bytes_out(&mut env, result)
}

/// Event lines since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_events<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_events().join("\n");
    match env.new_string(lines) {
        Ok(string) => string.into_raw(),
        Err(error) => {
            throw(&mut env, &error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_airgestures_Bridge_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Connection) });
    }
}
