//! The band's roll right now, for the calibration screen (its samples are what
//! `PalmCalibration::from_rolls` takes): from orientation events, read as band-core reads
//! them (`pinch::roll_or`, `up_in_band`). The palm position itself is `Controls::palm`.

use band_core::controls::pinch::{roll_or, up_in_band};
use band_core::events::Event;

/// A sample older than this isn't "right now" (band-core's `ORIENTATION_STALE`).
const FRESH: f64 = 0.5;

#[derive(Debug, Default)]
pub struct LiveRoll {
    /// The last readable roll (degrees) and when it came.
    last: Option<(f64, f64)>,
}

impl LiveRoll {
    pub fn on_event(&mut self, event: &Event, now: f64) {
        let Event::Orientation(q) = event else { return };
        let roll = roll_or(up_in_band(*q), f64::NAN);
        if roll.is_finite() {
            self.last = Some((roll, now));
        }
    }

    /// The roll (to 0.1°) when the last readable sample is fresh.
    pub fn fresh(&self, now: f64) -> Option<f64> {
        self.last.filter(|(_, at)| now - at < FRESH).map(|(roll, _)| (roll * 10.0).round() / 10.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An orientation whose roll (see `pinch::roll`) is `roll` degrees: turned about the band's y.
    fn rolled(roll: f64) -> Event {
        let half = (-roll).to_radians() / 2.0;
        Event::Orientation([half.cos() as f32, 0.0, half.sin() as f32, 0.0])
    }

    #[test]
    fn it_reads_the_roll_and_goes_stale() {
        let mut live = LiveRoll::default();
        assert_eq!(live.fresh(0.0), None);
        live.on_event(&rolled(-142.3), 1.0);
        assert_eq!(live.fresh(1.1), Some(-142.3));
        // Forearm straight down: no roll to read, the last one stays.
        let half = std::f32::consts::FRAC_1_SQRT_2;
        live.on_event(&Event::Orientation([half, half, 0.0, 0.0]), 1.2);
        assert_eq!(live.fresh(1.3), Some(-142.3));
        assert_eq!(live.fresh(1.6), None, "stale");
    }
}
