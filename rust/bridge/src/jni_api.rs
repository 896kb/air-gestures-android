//! JNI entry points for `dev.kb896.airgestures.Bridge` (static natives). A connection
//! is a boxed `Connection` behind a `long` handle, a claim a boxed `Claim`;
//! errors become IOException.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};

use crate::Connection;
use crate::claim::Claim;
use crate::metaauth;

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
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_open<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    owner_key: JByteArray<'local>,
    scheme_guess: jint,
    paused: jboolean,
    dial: JString<'local>,
    mapping: JString<'local>,
) -> jlong {
    let strings = env.get_string(&dial).map(String::from).and_then(|dial| {
        env.get_string(&mapping)
            .map(|mapping| (dial, String::from(mapping)))
    });
    let (dial, mapping) = match strings {
        Ok(strings) => strings,
        Err(error) => {
            throw(&mut env, &error.to_string());
            return 0;
        }
    };
    let opened = env
        .convert_byte_array(&owner_key)
        .map_err(|e| e.to_string())
        .and_then(|key| {
            Connection::new(
                &key,
                scheme_guess.max(0) as usize,
                paused != 0,
                &dial,
                &mapping,
            )
        });
    match opened {
        Ok(connection) => Box::into_raw(Box::new(connection)) as jlong,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_request<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.request();
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_feed<'local>(
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
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.tick(now);
    bytes_out(&mut env, result)
}

fn string_out(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(error) => {
            throw(env, &error.to_string());
            std::ptr::null_mut()
        }
    }
}

/// Action names to run since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_actions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_actions().join("\n");
    string_out(&mut env, lines)
}

/// Log lines since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_log<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { connection(handle) }.take_log().join("\n");
    string_out(&mut env, lines)
}

/// The status snapshot as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_status<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let json = unsafe { connection(handle) }.status_json();
    string_out(&mut env, json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setPaused<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    paused: jboolean,
    now: jdouble,
) {
    unsafe { connection(handle) }.set_paused(paused != 0, now);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setDial<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dial: JString<'local>,
) {
    match env.get_string(&dial) {
        Ok(dial) => unsafe { connection(handle) }.set_dial(&String::from(dial)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setMapping<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    mapping: JString<'local>,
) {
    match env.get_string(&mapping) {
        Ok(mapping) => unsafe { connection(handle) }.set_mapping(&String::from(mapping)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setProbe<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    fields: JString<'local>,
) {
    match env.get_string(&fields) {
        Ok(fields) => unsafe { connection(handle) }.set_probe(&String::from(fields)),
        Err(error) => throw(&mut env, &error.to_string()),
    }
}

/// Debug: the band's update status, read only; bytes to write (answer in the log).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_otaStatus<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.ota_status();
    bytes_out(&mut env, result)
}

/// Debug: one step of a firmware update (`mode`: key, send, trial, cancel, install) from the package
/// unzipped in `dir`. Bytes to write; progress goes to the log.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_otaStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    mode: JString<'local>,
    dir: JString<'local>,
    now: jdouble,
) -> jbyteArray {
    let strings = env.get_string(&mode).map(String::from).and_then(|mode| Ok((mode, String::from(env.get_string(&dir)?))));
    let result = match strings {
        Ok((mode, dir)) => unsafe { connection(handle) }.ota_start(&mode, &dir, now),
        Err(error) => Err(error.to_string()),
    };
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_probeConfig<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    hex: JString<'local>,
    now: jdouble,
) -> jbyteArray {
    let result = match env.get_string(&hex) {
        Ok(hex) => unsafe { connection(handle) }.probe_config(&String::from(hex), now),
        Err(error) => Err(error.to_string()),
    };
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setRawEmg<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
    now: jdouble,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.set_raw_emg(on != 0, now);
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dir: JString<'local>,
) {
    let result = get_string(&mut env, &dir).and_then(|dir| unsafe { connection(handle) }.record_start(&dir));
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordMark<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    event: JString<'local>,
    label: JString<'local>,
    now: jdouble,
) {
    let result = get_string(&mut env, &event).and_then(|event| {
        let label = get_string(&mut env, &label)?;
        unsafe { connection(handle) }.record_mark(&event, &label, now)
    });
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordStats<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let stats = unsafe { connection(handle) }.record_stats();
    json_out(&mut env, Ok(stats))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordGuess<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    label: JString<'local>,
    start: jdouble,
    end: jdouble,
) -> jstring {
    let guesses = get_string(&mut env, &label).map(|label| unsafe { connection(handle) }.record_guess(&label, start, end));
    json_out(&mut env, guesses)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordWrite<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    start: jdouble,
    end: jdouble,
) -> jstring {
    let state = unsafe { connection(handle) }.record_write(start, end);
    json_out(&mut env, Ok(state))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordEdit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    edit: JString<'local>,
    now: jdouble,
) -> jstring {
    let state = get_string(&mut env, &edit).map(|edit| unsafe { connection(handle) }.record_edit(&edit, now));
    json_out(&mut env, state)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_deviceInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let info = unsafe { connection(handle) }.device_info();
    json_out(&mut env, Ok(info))
}

/// Debug: check, download and keep the firmware Meta offers this band (blocking HTTP: call off
/// the main thread). A summary as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_firmwareArchive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: JString<'local>,
    serial: JString<'local>,
    version: JString<'local>,
    dir: JString<'local>,
) -> jstring {
    let result = (|| {
        let session = get_string(&mut env, &session)?;
        let serial = get_string(&mut env, &serial)?;
        let version = get_string(&mut env, &version)?;
        let dir = get_string(&mut env, &dir)?;
        crate::firmware::archive(&session, &serial, &version, &dir)
    })();
    json_out(&mut env, result)
}

/// The band's handwriting into the text field in front, on or off; bytes to write.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setFieldTyping<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
    before: JString<'local>,
) -> jbyteArray {
    let result = match env.get_string(&before) {
        Ok(before) => unsafe { connection(handle) }.set_field_typing(on != 0, &String::from(before)),
        Err(error) => Err(error.to_string()),
    };
    bytes_out(&mut env, result)
}

/// The text field's edits since the last call, as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldEdits<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let json = unsafe { connection(handle) }.field_edits();
    json_out(&mut env, Ok(json))
}

/// Debug: the band's own handwriting on or off; bytes to write.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setBandHandwriting<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
) -> jbyteArray {
    let result = unsafe { connection(handle) }.set_band_handwriting(on != 0);
    bytes_out(&mut env, result)
}

/// Debug: what the band's handwriting has sent, as JSON; `clear` forgets it afterwards.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_bandHandwriting<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    clear: jboolean,
) -> jstring {
    let json = unsafe { connection(handle) }.band_handwriting(clear != 0);
    json_out(&mut env, Ok(json))
}

/// The firmware download in progress as `{"version", "done", "total"}` (`{}` when none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_firmwareProgress<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let progress = crate::firmware::download_progress().map_or_else(
        || "{}".to_owned(),
        |(version, done, total)| serde_json::json!({"version": version, "done": done, "total": total}).to_string(),
    );
    json_out(&mut env, Ok(progress))
}

/// The handwriting dictionary (`word count` lines); how many words it has.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_wordsLoad<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jint {
    match get_string(&mut env, &text) {
        Ok(text) => crate::words::load_dictionary(&text) as jint,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_recordStop<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Err(message) = unsafe { connection(handle) }.record_stop() {
        throw(&mut env, &message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Connection) });
    }
}

// ---- Meta sign-in (blocking HTTP: call off the main thread) ----

fn get_string(env: &mut JNIEnv, string: &JString) -> Result<String, String> {
    env.get_string(string)
        .map(String::from)
        .map_err(|e| e.to_string())
}

fn json_out(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(text) => string_out(env, text),
        Err(message) => {
            throw(env, &message);
            std::ptr::null_mut()
        }
    }
}

/// `{"url": Meta's sign-in page, "native": the token that checks and finishes the callback}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_signinStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let result = metaauth::tokens_query().map(|tokens| {
        serde_json::json!({
            "url": metaauth::auth_entry_url(&tokens),
            "native": tokens.native_sso_token,
        })
        .to_string()
    });
    json_out(&mut env, result)
}

/// A navigation the web view must cancel: the sign-in callback's shape.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_signinIsCallback<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
) -> jboolean {
    match get_string(&mut env, &url) {
        Ok(url) => metaauth::is_callback(&url) as jboolean,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

/// The callback is the real one: the token digest of `native` and a non-empty blob.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_signinAccepts<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    native: JString<'local>,
) -> jboolean {
    let strings = get_string(&mut env, &url)
        .and_then(|url| get_string(&mut env, &native).map(|native| (url, native)));
    match strings {
        Ok((url, native)) => metaauth::accepts_callback(&url, &native) as jboolean,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

/// Decrypt the callback and log in: the session JSON to keep (secret; never log it).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_signinFinish<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    native: JString<'local>,
) -> jstring {
    let result = get_string(&mut env, &url)
        .and_then(|url| get_string(&mut env, &native).map(|native| (url, native)))
        .and_then(|(url, native)| metaauth::finish(&url, &native))
        .and_then(|session| serde_json::to_string(&session).map_err(|e| e.to_string()));
    json_out(&mut env, result)
}

// ---- The ownership claim: a boxed `Claim` behind a `long` handle ----

/// # Safety
/// `handle` must come from `claimOpen` and not have been passed to `claimClose`.
unsafe fn claim<'a>(handle: jlong) -> &'a mut Claim {
    unsafe { &mut *(handle as *mut Claim) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_json: JString<'local>,
    files_dir: JString<'local>,
    scheme_guess: jint,
) -> jlong {
    let opened = get_string(&mut env, &session_json)
        .and_then(|json| get_string(&mut env, &files_dir).map(|dir| (json, dir)))
        .and_then(|(json, dir)| Claim::new(&json, dir, scheme_guess.max(0) as usize));
    match opened {
        Ok(claim) => Box::into_raw(Box::new(claim)) as jlong,
        Err(message) => {
            throw(&mut env, &message);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimRequest<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let bytes = unsafe { claim(handle) }.request();
    bytes_out(&mut env, Ok(bytes))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimFeed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bytes: JByteArray<'local>,
    now: jdouble,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&bytes)
        .map_err(|e| e.to_string())
        .map(|bytes| unsafe { claim(handle) }.feed(&bytes, now));
    bytes_out(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimTick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jbyteArray {
    let bytes = unsafe { claim(handle) }.tick(now);
    bytes_out(&mut env, Ok(bytes))
}

/// The channel closed or failed while the claim was running (`message`: why, or empty).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimClosed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    message: JString<'local>,
) {
    match get_string(&mut env, &message) {
        Ok(message) => unsafe { claim(handle) }.closed(&message),
        Err(message) => throw(&mut env, &message),
    }
}

/// Progress lines since the last call, joined with '\n' (empty: none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimLog<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let lines = unsafe { claim(handle) }.take_log().join("\n");
    string_out(&mut env, lines)
}

/// "running", "done", "failed: …" or "expired: …" (the Meta sign-in must be redone).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimState<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let state = unsafe { claim(handle) }.state().to_string();
    string_out(&mut env, state)
}

/// After a failure: the next encryption guess worth a fresh channel, or -1.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimNextGuess<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    unsafe { claim(handle) }
        .next_guess()
        .map_or(-1, |guess| guess as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_claimClose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Claim) });
    }
}
