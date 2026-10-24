//! Firmware archive (debug): ask Meta, as the Meta AI app does, which firmware it offers this
//! band, and keep a copy of it without installing anything. Worked out from Meta AI
//! 295.0.0.37.164 (scratchpad notes `FIRMWARE-DOWNLOAD.md`): a GraphQL query with the
//! signed-in user's token gives a plain download link; the package is encrypted for the band,
//! and installing it would also need the band's wrapped key (`kek_id`), which is fetched and
//! kept alongside in case Meta stops serving it.
//!
//! Each check saves `<dir>/<target version>/`: `ota.zip`, `response.json` (the offer),
//! `kek.json` (the wrapped key, when one is named) and `check.json` (what was asked, when).

use std::fs;
use std::io::{Read, Write};
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::meta::MetaSession;

const HOSTS: [&str; 2] = ["ar-genai.graph.meta.com", "ar.graph.meta.com"];
const USER_AGENT: &str = "Dalvik/2.1.0 (Linux; U; Android 14) [FBAN/StellaForAndroid;FBAV/295.0.0.37.164;FBPN/com.facebook.stella;FBLC/en_US;FBSN/Android]";
/// The app's persisted query ids (assets/companion-ar_client_persist_ids.json).
const FETCH_SYSTEM_UPDATES: &str = "29744781653741296994778085945";
const FETCH_CONSTELLATION_UPDATES: &str = "155904535318377635033579516369";
const FETCH_KEK: &str = "313699925716490204858433749875";

fn http(timeout: u64) -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(timeout))
        .user_agent(USER_AGENT)
        .build()
        .map_err(|e| format!("could not start an HTTP client: {e}"))
}

/// One GraphQL query; the response's JSON (Meta sometimes puts `for (;;);` first).
fn graphql(session: &MetaSession, host: &str, name: &str, id: &str, variables: &Value) -> Result<Value, String> {
    let body = crate::meta::url_form(&[
        ("method", "post".into()),
        ("pretty", "false".into()),
        ("format", "json".into()),
        ("server_timestamps", "true".into()),
        ("locale", "en_US".into()),
        ("purpose", "fetch".into()),
        ("fb_api_req_friendly_name", name.into()),
        ("client_doc_id", id.into()),
        ("variables", variables.to_string()),
    ]);
    let response = http(60)?
        .post(format!("https://{host}/graphql"))
        .header("Authorization", format!("OAuth {}", session.access_token))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("X-FB-Friendly-Name", name)
        .body(body)
        .send()
        .map_err(|e| format!("couldn't reach Meta: {e}"))?;
    let status = response.status().as_u16();
    let text = response.text().map_err(|e| e.to_string())?;
    let text = text.trim_start().trim_start_matches("for (;;);");
    let json: Value = serde_json::from_str(text).map_err(|_| format!("Meta answered http {status}, not JSON"))?;
    if status != 200 {
        return Err(format!("Meta answered http {status}: {}", errors(&json)));
    }
    Ok(json)
}

/// Meta's error messages, without anything else from the response.
fn errors(json: &Value) -> String {
    let messages: Vec<String> = json["errors"]
        .as_array()
        .into_iter()
        .flatten()
        .chain(json.get("error"))
        .filter_map(|e| e["message"].as_str().or(e["summary"].as_str()).map(str::to_owned))
        .collect();
    if messages.is_empty() { "no message".into() } else { messages.join("; ") }
}

/// The offer in a response, legacy or unified: the object with `file_uri`.
fn offer(json: &Value) -> Option<&Value> {
    match json {
        Value::Object(map) if map.contains_key("file_uri") => Some(json),
        Value::Object(map) => map.values().find_map(offer),
        Value::Array(items) => items.iter().find_map(offer),
        _ => None,
    }
}

/// Ask for this band's firmware every way the app does: each offer's response with a note of
/// what was asked (the ways can offer different builds), and what the others answered.
fn check(session: &MetaSession, serial: &str, version: &str) -> (Vec<(Value, Value)>, Vec<String>) {
    let mut offers = Vec::new();
    let mut tried = Vec::new();
    for host in HOSTS {
        let legacy = json!({"fetch_request": {"topology_fetch_update_info": [{
            "custom_ota_fetch_type": "CERES_USER_INSIGHTS_NO_DEVICE_IDENTITY",
            "full_update_only": true,
            "device_serial_number": serial,
            "device_type": "ota.ceres_p1.user",
            "fw_version": version,
        }]}});
        let unified = json!({"fetch_request": {"constellation_updates_request_info": [{
            "device_type": "ota.ceres.user",
            "device_serial": serial,
            "device_ota_session_id": uuid::Uuid::new_v4().to_string(),
            "device_firmware_version": version,
            "device_feature_list": [],
            "device_artifact_list": [],
            "device_firmware_full_update_only": true,
            "device_firmware_rollout_token": null,
            "device_manage_mode": null,
            "constellation_device_type": null,
            "constellation_device_firmware_version": null,
            "constellation_device_serial": null,
            "supports_firmware_manifest_v1": true,
        }]}});
        for (name, id, variables) in [
            ("FetchSystemUpdates", FETCH_SYSTEM_UPDATES, &legacy),
            ("FetchConstellationUpdates", FETCH_CONSTELLATION_UPDATES, &unified),
        ] {
            match graphql(session, host, name, id, variables) {
                Ok(json) if offer(&json).is_some() => {
                    let asked = json!({"host": host, "query": name, "version": version, "at": now()});
                    offers.push((json, asked));
                }
                Ok(json) if json.get("errors").is_some() => tried.push(format!("{host} {name}: {}", errors(&json))),
                Ok(_) => tried.push(format!("{host} {name}: no update offered")),
                Err(error) => tried.push(format!("{host} {name}: {error}")),
            }
        }
    }
    (offers, tried)
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// The download in progress: the build's target version, bytes so far, bytes in all (0 unknown).
static DOWNLOADING: std::sync::Mutex<Option<(String, u64, u64)>> = std::sync::Mutex::new(None);

/// What's downloading now, for a progress meter: `(target version, done, total)`.
pub fn download_progress() -> Option<(String, u64, u64)> {
    DOWNLOADING.lock().ok()?.clone()
}

fn set_progress(progress: Option<(String, u64, u64)>) {
    if let Ok(mut slot) = DOWNLOADING.lock() {
        *slot = progress;
    }
}

/// Download `url` to `path`, reporting progress as `target`; its size and SHA-256 (hex).
fn download(url: &str, path: &Path, target: &str, expected: u64) -> Result<(u64, String), String> {
    set_progress(Some((target.to_owned(), 0, expected)));
    let result = fetch(url, path, target, expected);
    set_progress(None);
    result
}

fn fetch(url: &str, path: &Path, target: &str, expected: u64) -> Result<(u64, String), String> {
    let mut response = http(1800)?.get(url).send().map_err(|e| format!("download failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("download failed: http {}", response.status().as_u16()));
    }
    let total = response.content_length().unwrap_or(expected);
    let partial = path.with_extension("part");
    let mut file = fs::File::create(&partial).map_err(|e| e.to_string())?;
    let mut hash = Sha256::new();
    let mut size = 0u64;
    let mut buffer = vec![0u8; 1 << 16];
    loop {
        let n = response.read(&mut buffer).map_err(|e| format!("download failed: {e}"))?;
        if n == 0 {
            break;
        }
        hash.update(&buffer[..n]);
        file.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
        size += n as u64;
        set_progress(Some((target.to_owned(), size, total)));
    }
    file.flush().map_err(|e| e.to_string())?;
    fs::rename(&partial, path).map_err(|e| e.to_string())?;
    Ok((size, hex(&hash.finalize())))
}

fn text(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

/// Check, download and keep every firmware offered for `serial` (reporting `version` as the
/// band's current one; "1" asks for a full build) under `dir`. A summary as JSON.
pub fn archive(session_json: &str, serial: &str, version: &str, dir: &str) -> Result<String, String> {
    let session: MetaSession = serde_json::from_str(session_json).map_err(|_| "no Meta sign-in".to_owned())?;
    if let Some(version) = version.strip_prefix("rest:") {
        return rest(&session, serial, version, dir);
    }
    let (offers, tried) = check(&session, serial, version);
    if offers.is_empty() {
        return Err(tried.join("\n"));
    }
    let mut kept = Vec::new();
    let mut seen = Vec::new();
    for (response, asked) in offers {
        let offer = offer(&response).expect("an offer").clone();
        let target = offer.get("target_version").and_then(text).unwrap_or_else(|| "unknown".into());
        if seen.contains(&target) {
            kept.push(json!({"target_version": target, "asked": asked, "same_as_above": true}));
            continue;
        }
        seen.push(target.clone());
        kept.push(keep(&session, serial, dir, &response, &offer, &target, asked)?);
    }
    Ok(json!({"offers": kept, "not_offered": tried}).to_string())
}

/// One offer into `<dir>/<target>/`; an archived package with the right checksum isn't fetched again.
fn keep(session: &MetaSession, serial: &str, dir: &str, response: &Value, offer: &Value, target: &str, asked: Value) -> Result<Value, String> {
    let folder = Path::new(dir).join(target);
    fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
    let save = |name: &str, value: &Value| {
        fs::write(folder.join(name), serde_json::to_string_pretty(value).expect("JSON")).map_err(|e| e.to_string())
    };
    save("response.json", response)?;
    save("check.json", &asked)?;

    let zip = folder.join("ota.zip");
    let checksum = offer.get("file_checksum").and_then(text).unwrap_or_default();
    let (size, matches) = match fs::read(&zip) {
        Ok(bytes) if hex(&Sha256::digest(&bytes)).eq_ignore_ascii_case(&checksum) => (bytes.len() as u64, true),
        _ => {
            let url = offer.get("file_uri").and_then(Value::as_str).ok_or("the offer has no link")?;
            let expected = offer.get("file_size").and_then(text).and_then(|s| s.parse().ok()).unwrap_or(0);
            let (size, sha256) = download(url, &zip, target, expected)?;
            (size, checksum.eq_ignore_ascii_case(&sha256))
        }
    };

    let kek = match offer.get("kek_id").and_then(Value::as_i64) {
        Some(kek_id) => {
            let variables = json!({"request": {"device_serial_number": serial, "kek_id": kek_id}});
            let host = asked["host"].as_str().unwrap_or(HOSTS[0]);
            match graphql(session, host, "FetchEncryptedKEKInfoByKEKId", FETCH_KEK, &variables) {
                Ok(json) => {
                    save("kek.json", &json)?;
                    json!({"kek_id": kek_id, "saved": json.get("errors").is_none()})
                }
                Err(error) => json!({"kek_id": kek_id, "error": error}),
            }
        }
        None => Value::Null,
    };
    Ok(json!({
        "folder": folder.to_string_lossy(),
        "target_version": target,
        "base_version": offer.get("base_version"),
        "name": offer.get("build_number_display_name"),
        "release_tag": offer.get("release_tag"),
        "size": size,
        "checksum_matches": matches,
        "kek": kek,
        "asked": asked,
    }))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The glasses' REST check (`/firmware_ota_update`, seen in the app's glasses updater), asked
/// for the band: what each host and device type answers, saved under `<dir>/rest/`.
fn rest(session: &MetaSession, serial: &str, version: &str, dir: &str) -> Result<String, String> {
    const FIELDS: &str = "update_interval,ota{download_uri,target_version,base_version,file_size,release_channel_id,\
        file_checksum,release_tag,release_notes_cms,extra_install_options,build_number_display_name},\
        metadata{release_notes,build_number_display_name}";
    let folder = Path::new(dir).join("rest");
    fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
    let client = http(60)?;
    let mut answers = Vec::new();
    for host in HOSTS {
        for device_type in ["ota.ceres.user", "ota.ceres_p1.user"] {
            let response = client
                .get(format!("https://{host}/firmware_ota_update"))
                .query(&[
                    ("access_token", session.access_token.as_str()),
                    ("device_type", device_type),
                    ("version", version),
                    ("device_serial", serial),
                    ("full_update_only", "true"),
                    ("fields", FIELDS),
                ])
                .send();
            let answer = match response {
                Err(e) => json!({"host": host, "device_type": device_type, "error": format!("couldn't reach Meta: {}", e.without_url())}),
                Ok(response) => {
                    let status = response.status().as_u16();
                    let text = response.text().unwrap_or_default();
                    let text = text.trim_start().trim_start_matches("for (;;);");
                    let _ = fs::write(folder.join(format!("{host}-{device_type}.json")), text);
                    let json: Value = serde_json::from_str(text).unwrap_or(Value::Null);
                    let ota = &json["ota"];
                    json!({
                        "host": host, "device_type": device_type, "status": status,
                        "target_version": ota.get("target_version"), "name": ota.get("build_number_display_name"),
                        "keys": json.as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()),
                        "errors": if status == 200 { Value::Null } else { errors(&json).into() },
                    })
                }
            };
            answers.push(answer);
        }
    }
    Ok(json!({"rest": answers}).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_offer_is_found_in_either_response() {
        let legacy = json!({"data": {"xar_fetch_constellation_system_updates": {"fetch_update_info_list": [
            {"fetch_update_response": {"file_uri": "https://x/ota.zip", "target_version": "123"}}]}}});
        assert_eq!(offer(&legacy).unwrap()["target_version"], "123");
        let unified = json!({"data": {"xar_constellation_updates": {"constellation_updates_response_info": [
            {"device_serial": "S", "firmware_update_info": {"file_uri": "u", "target_version": 9}}]}}});
        assert_eq!(text(&offer(&unified).unwrap()["target_version"]).unwrap(), "9");
        assert!(offer(&json!({"data": {"x": []}})).is_none());
    }
}

/// A package unzipped into `dir` (`manifest.json`, the items it lists, and `kek.json` as
/// archived) as an update for the band: each item's size and SHA-256 checked against the
/// manifest. Also returns a summary for the log.
pub fn ota_plan(dir: &str, mode: band_core::ota::OtaMode) -> Result<(band_core::ota::OtaPlan, String), String> {
    use base64::Engine;
    use md5::Md5;
    let dir = Path::new(dir);
    let manifest: Value = serde_json::from_slice(&fs::read(dir.join("manifest.json")).map_err(|e| format!("manifest: {e}"))?)
        .map_err(|e| format!("manifest: {e}"))?;
    let items = manifest["items"].as_array().ok_or("manifest: no items")?;
    let mut version = None;
    let mut firmware = None;
    let mut model = None;
    let mut summary = Vec::new();
    for item in items {
        let path = item["path"].as_str().ok_or("manifest: item without a path")?;
        if path.contains("..") || path.starts_with('/') {
            return Err(format!("manifest: odd path {path}"));
        }
        let data = fs::read(dir.join(path)).map_err(|e| format!("{path}: {e}"))?;
        if item["size"].as_u64() != Some(data.len() as u64) {
            return Err(format!("{path}: {} bytes, the manifest says {}", data.len(), item["size"]));
        }
        if item["digest"].as_str() != Some(hex(&Sha256::digest(&data)).as_str()) {
            return Err(format!("{path}: SHA-256 doesn't match the manifest"));
        }
        let this = item["version"].as_u64().ok_or("manifest: item without a version")?;
        if version.is_some_and(|v| v != this) {
            return Err("manifest: the items have different versions".into());
        }
        version = Some(this);
        let md5: [u8; 16] = Md5::digest(&data).into();
        summary.push(format!("{path} {} bytes", data.len()));
        match item["item_type"].as_u64() {
            Some(1) => firmware = Some((data, md5)),
            Some(2) => model = Some((data, md5)),
            other => return Err(format!("{path}: unknown item type {other:?}")),
        }
    }
    let (firmware, firmware_md5) = firmware.ok_or("manifest: no firmware item")?;
    let kek_id = manifest["kek_version"].as_u64().ok_or("manifest: no kek_version")?;
    let kek = match fs::read(dir.join("kek.json")) {
        Ok(bytes) => {
            let json: Value = serde_json::from_slice(&bytes).map_err(|e| format!("kek.json: {e}"))?;
            let info = &json["data"]["xar_fetch_encrypted_kek_info_by_kek_id"];
            if info["encryption_algo"].as_str() != Some("AES_128") {
                return Err(format!("kek.json: unexpected algorithm {}", info["encryption_algo"]));
            }
            let key = base64::engine::general_purpose::STANDARD
                .decode(info["encrypted_kek"].as_str().ok_or("kek.json: no key")?)
                .map_err(|e| format!("kek.json: {e}"))?;
            summary.push(format!("key {kek_id} ({} bytes, wrapped)", key.len()));
            Some((kek_id, key))
        }
        Err(_) => {
            summary.push(format!("no kek.json (the package needs key {kek_id})"));
            None
        }
    };
    let version = version.ok_or("manifest: no version")?;
    let plan = band_core::ota::OtaPlan { version, firmware, firmware_md5, model, kek, mode, resume: false };
    Ok((plan, format!("package {version}: {}", summary.join(", "))))
}

#[cfg(test)]
mod package_tests {
    /// `OTA=<dir>`: an unzipped package (with kek.json) reads as a plan.
    #[test]
    #[ignore]
    fn package_reads_as_a_plan() {
        let dir = std::env::var("OTA").expect("OTA=<unzipped package>");
        let (plan, summary) = super::ota_plan(&dir, band_core::ota::OtaMode::Trial).unwrap();
        println!("{summary}");
        assert!(plan.kek.is_some() && plan.model.is_some());
    }
}
