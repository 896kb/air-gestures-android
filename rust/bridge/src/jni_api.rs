//! JNI entry points for `dev.kb896.airgestures.Bridge` (static natives). A connection
//! is a boxed `Connection` behind a `long` handle, a claim a boxed `Claim`;
//! errors become IOException.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JDoubleArray, JString};
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
    profiles: JString<'local>,
) -> jlong {
    let profiles = match get_string(&mut env, &profiles) {
        Ok(profiles) => profiles,
        Err(message) => {
            throw(&mut env, &message);
            return 0;
        }
    };
    let opened = env
        .convert_byte_array(&owner_key)
        .map_err(|e| e.to_string())
        .and_then(|key| Connection::new(&key, scheme_guess.max(0) as usize, paused != 0, &profiles));
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

macro_rules! haptics_method {
    ($name:ident, $method:ident, $arg:ty, $convert:expr) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $name<'local>(
            mut env: JNIEnv<'local>,
            _class: JClass<'local>,
            handle: jlong,
            value: $arg,
        ) -> jbyteArray {
            bytes_out(&mut env, unsafe { connection(handle) }.$method(($convert)(value)))
        }
    };
}

haptics_method!(Java_dev_kb896_airgestures_Bridge_setBandVibration, set_band_vibration, jboolean, |value| value != 0);
haptics_method!(Java_dev_kb896_airgestures_Bridge_controlsHaptics, controls_haptics, jboolean, |value| value != 0);
haptics_method!(Java_dev_kb896_airgestures_Bridge_syncControlsHaptics, sync_controls_haptics, jboolean, |value| value != 0);
haptics_method!(Java_dev_kb896_airgestures_Bridge_profileHaptics, profile_haptics, jboolean, |value| value != 0);

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_volumeDetent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    bytes_out(&mut env, unsafe { connection(handle) }.volume_detent())
}

/// New profiles, hand and call layer as JSON (see `profiles::parse`); bad JSON throws.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setProfiles<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    json: JString<'local>,
) {
    let result = get_string(&mut env, &json).and_then(|json| unsafe { connection(handle) }.set_profiles(&json));
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

/// The app in front (`""`: none known).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setForeground<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    app: JString<'local>,
) {
    let result = get_string(&mut env, &app).and_then(|app| unsafe { connection(handle) }.set_foreground(&app));
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setInCall<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    in_call: jboolean,
) {
    unsafe { connection(handle) }.set_in_call(in_call != 0);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setPractice<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    practice: jboolean,
) {
    unsafe { connection(handle) }.set_practice(practice != 0);
}

/// The drawings draw sessions match against, as JSON (see `teach::library`); bad JSON throws
/// and keeps the ones before.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setLibrary<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    json: JString<'local>,
) {
    let result = get_string(&mut env, &json).and_then(|json| unsafe { connection(handle) }.set_library(&json));
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

/// Open a draw session from the app (`@draw-started`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_startDrawing<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) {
    if let Err(message) = unsafe { connection(handle) }.start_drawing(now) {
        throw(&mut env, &message);
    }
}

/// The pad's close button (`@draw-cancelled`; nothing with no session).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_cancelDrawing<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Err(message) = unsafe { connection(handle) }.cancel_drawing() {
        throw(&mut env, &message);
    }
}

/// Teaching drawings: sessions report their strokes and match nothing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setTeaching<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
) {
    unsafe { connection(handle) }.set_teaching(on != 0);
}

/// Teaching drags: pinches report `@drag-sample` and nothing acts; on cancels an open drawing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setDragTeaching<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
) {
    if let Err(message) = unsafe { connection(handle) }.set_drag_teaching(on != 0) {
        throw(&mut env, &message);
    }
}

/// The palm and roll right now, as JSON (see `Connection::palm_now`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_palmNow<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jstring {
    let json = unsafe { connection(handle) }.palm_now(now);
    string_out(&mut env, json)
}

/// What the screens show about a profiles JSON (see `report::report`); invalid JSON: `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_profileReport<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
) -> jstring {
    match get_string(&mut env, &json) {
        Ok(json) => string_out(&mut env, crate::report::report(&json)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

/// Where a band control could go and what it would pause (see `report::band_choices`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_bandChoices<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    json: JString<'local>,
    control: JString<'local>,
    palm: JString<'local>,
) -> jstring {
    let strings = get_string(&mut env, &json)
        .and_then(|json| Ok((json, get_string(&mut env, &control)?, get_string(&mut env, &palm)?)));
    match strings {
        Ok((json, control, palm)) => string_out(&mut env, crate::report::band_choices(&json, &control, &palm)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

fn doubles(env: &mut JNIEnv, array: &JDoubleArray) -> Result<Vec<f64>, String> {
    let length = env.get_array_length(array).map_err(|e| e.to_string())?;
    let mut values = vec![0.0; length.max(0) as usize];
    env.get_double_array_region(array, 0, &mut values).map_err(|e| e.to_string())?;
    Ok(values)
}

/// A palm calibration from rolls held still palm down and palm up (see
/// `teach::palm_calibration`): `{"down", "up"}` or `{"error"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_palmCalibration<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    down: JDoubleArray<'local>,
    up: JDoubleArray<'local>,
) -> jstring {
    let rolls = doubles(&mut env, &down).and_then(|down| Ok((down, doubles(&mut env, &up)?)));
    match rolls {
        Ok((down, up)) => string_out(&mut env, crate::teach::palm_calibration(&down, &up)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

/// Taught drags from their samples (see `teach::drag_prototypes`): the prototypes or `{"error"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_dragPrototypes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    samples: JString<'local>,
) -> jstring {
    match get_string(&mut env, &samples) {
        Ok(samples) => string_out(&mut env, crate::teach::drag_prototypes(&samples)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

/// Whether these tries can teach a drawing (see `teach::check_tries`): `""` or why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_checkTries<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tries: JString<'local>,
) -> jstring {
    match get_string(&mut env, &tries) {
        Ok(tries) => string_out(&mut env, crate::teach::check_tries(&tries)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
    }
}

/// The drawing in the library these tries look like: `{"like": name | null}`, or
/// `{"error": "bad-library" | "bad-tries"}` (see `teach::looks_like`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_looksLike<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    library: JString<'local>,
    tries: JString<'local>,
) -> jstring {
    let strings = get_string(&mut env, &library).and_then(|library| Ok((library, get_string(&mut env, &tries)?)));
    match strings {
        Ok((library, tries)) => string_out(&mut env, crate::teach::looks_like(&library, &tries)),
        Err(message) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
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

/// The band's handwriting into the text field in front, on or off; bytes to write. On throws
/// while the controls are off.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_setFieldTyping<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    on: jboolean,
    before: JString<'local>,
    popup: jboolean,
    now: jdouble,
) -> jbyteArray {
    let result = match env.get_string(&before) {
        Ok(before) => unsafe { connection(handle) }.set_field_typing(on != 0, &String::from(before), popup != 0, now),
        Err(error) => Err(error.to_string()),
    };
    bytes_out(&mut env, result)
}

/// A swipe left in a text field: take back the last word; false when none of ours was left.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldDeleteWord<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jboolean {
    jboolean::from(unsafe { connection(handle) }.field_delete_word(now))
}

/// A swipe up in a text field: show the word's suggestions; false when there are none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldChoose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    jboolean::from(unsafe { connection(handle) }.field_choose())
}

/// While choosing: the next (`forward`) or previous suggestion first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldChooseStep<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    forward: jboolean,
) {
    unsafe { connection(handle) }.field_choose_step(forward != 0);
}

/// While choosing: take the first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldChoosePick<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) {
    unsafe { connection(handle) }.field_choose_pick(now);
}

/// While choosing: leave it with the word as it was.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldChooseCancel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) {
    unsafe { connection(handle) }.field_choose_cancel(now);
}

/// A double tap in a text field: the pen goes down.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldPenDown<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) {
    unsafe { connection(handle) }.field_pen_down(now);
}

/// The text field's edits since the last call, as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_kb896_airgestures_Bridge_fieldEdits<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jdouble,
) -> jstring {
    let json = unsafe { connection(handle) }.field_edits(now);
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
