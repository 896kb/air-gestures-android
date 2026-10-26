//! What the app shows: the controls' view of the band (the link state and the firmware
//! update's are added by `Connection`).

use serde::Serialize;

#[derive(Debug, Clone, Default, Serialize)]
pub struct Status {
    /// Controls are off (the link can still be up).
    pub paused: bool,
    pub battery: Option<u8>,
    pub charging: Option<bool>,
    /// "left" or "right" as confirmed by the band.
    pub hand: Option<String>,
    pub last_gesture: Option<String>,
    pub last_action: Option<String>,
    /// Recognized gestures this connection.
    pub gestures: u64,
}
